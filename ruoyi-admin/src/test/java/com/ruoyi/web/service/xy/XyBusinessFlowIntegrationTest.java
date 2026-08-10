package com.ruoyi.web.service.xy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;

/**
 * 商城、预约、会员和核销的真实数据库/Redis集成测试。
 * 测试数据由事务自动回滚，Redis会话在结束时显式清除。
 */
@SpringBootTest(
        classes = RuoYiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "TOKEN_SECRET=integration-test-token-secret-at-least-32-bytes",
                "xy.wechat-pay.demo-enabled=true"
        })
@EnabledIfEnvironmentVariable(named = "RUN_XY_INTEGRATION_TESTS", matches = "true")
@Transactional
class XyBusinessFlowIntegrationTest
{
    private static final String MEMBER_TOKEN_PREFIX = "xy:member:token:";

    @Autowired
    private XyBusinessService service;

    @Autowired
    private XyWechatPayService payService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RedisCache redisCache;

    private String memberToken;

    @AfterEach
    void clearRedisSession()
    {
        if (memberToken != null)
        {
            redisCache.deleteObject(MEMBER_TOKEN_PREFIX + memberToken);
        }
    }

    @Test
    void completeCommercialCoreFlow()
    {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> login = service.loginByOpenId("e2e_" + suffix, null);
        memberToken = String.valueOf(login.get("memberToken"));
        Long memberId = service.requireMember(memberToken);
        assertNotNull(memberId);

        jdbc.update("insert into xy_store(store_name,address,phone,business_hours) values(?,?,?,?)",
                "集成测试门店" + suffix, "测试地址", "13800000000", "09:00-22:00");
        Long storeId = jdbc.queryForObject("select last_insert_id()", Long.class);
        jdbc.update("insert into xy_reservation_slot(store_id,start_time,end_time) values(?,'09:00:00','10:00:00')", storeId);
        Long slotId = jdbc.queryForObject("select last_insert_id()", Long.class);
        jdbc.update("insert into xy_seat(store_id,seat_code,zone_name) values(?,?,?)", storeId, "A-" + suffix.substring(0, 6), "测试区");
        Long seatId = jdbc.queryForObject("select last_insert_id()", Long.class);
        jdbc.update("insert into xy_membership_plan(plan_name,amount,duration_days,daily_reservation_limit) values(?,?,?,?)",
                "集成测试套餐" + suffix, new BigDecimal("99.00"), 30, 3);
        Long planId = jdbc.queryForObject("select last_insert_id()", Long.class);
        jdbc.update("insert into xy_membership_card(member_id,plan_id,card_no,start_date,expire_date) values(?,?,?,?,?)",
                memberId, planId, "TC" + suffix.substring(0, 20), LocalDate.now(), LocalDate.now().plusDays(30));

        Map<String, Object> address = new HashMap<>();
        address.put("receiverName", "测试会员");
        address.put("receiverMobile", "13800000000");
        address.put("province", "广东省");
        address.put("city", "深圳市");
        address.put("district", "南山区");
        address.put("detail", "测试路1号");
        address.put("isDefault", true);
        assertNotNull(service.saveAddress(memberId, address));

        LocalDate tomorrow = LocalDate.now().plusDays(1);
        Map<String, Object> firstReservation = service.createReservation(memberId, storeId, slotId, seatId, tomorrow);
        assertThrows(ServiceException.class, () -> service.createReservation(memberId, storeId, slotId, seatId, tomorrow));
        assertThrows(ServiceException.class, () -> service.createReservation(memberId, storeId, slotId, seatId, tomorrow.plusDays(1)));
        service.cancelReservation(memberId, String.valueOf(firstReservation.get("reservationNo")));
        Map<String, Object> secondReservation = service.createReservation(memberId, storeId, slotId, seatId, tomorrow);
        service.cancelReservation(memberId, String.valueOf(secondReservation.get("reservationNo")));
        assertEquals(2, jdbc.queryForObject("select count(1) from xy_reservation where member_id=?", Integer.class, memberId));

        jdbc.update("insert into xy_product(product_name,category_name,sale_price,stock) values(?,?,?,?)",
                "集成测试商品" + suffix, "测试", new BigDecimal("12.50"), 10);
        Long productId = jdbc.queryForObject("select last_insert_id()", Long.class);
        Map<String, Object> orderInput = new HashMap<>();
        orderInput.put("productId", productId);
        orderInput.put("quantity", 2);
        orderInput.put("deliveryType", "PICKUP");
        Map<String, Object> canceledOrder = service.createOrder(memberId, orderInput);
        assertEquals(8, jdbc.queryForObject("select stock from xy_product where product_id=?", Integer.class, productId));
        service.cancelOrder(memberId, String.valueOf(canceledOrder.get("orderNo")));
        assertEquals(10, jdbc.queryForObject("select stock from xy_product where product_id=?", Integer.class, productId));

        Map<String, Object> expiredOrder = service.createOrder(memberId, orderInput);
        jdbc.update("update xy_order set create_time=date_sub(now(),interval 31 minute) where order_id=?", expiredOrder.get("orderId"));
        service.maintainExpiredBusinessRecords();
        assertEquals("CANCELED", jdbc.queryForObject("select status from xy_order where order_id=?", String.class, expiredOrder.get("orderId")));
        assertEquals(10, jdbc.queryForObject("select stock from xy_product where product_id=?", Integer.class, productId));

        orderInput.put("quantity", 1);
        Map<String, Object> paidOrder = service.createOrder(memberId, orderInput);
        Long orderId = ((Number) paidOrder.get("orderId")).longValue();
        String paymentNo = "TP" + suffix.substring(0, 28);
        jdbc.update("insert into xy_payment(payment_no,member_id,business_type,business_id,amount,channel) values(?,?,?,?,?,'WECHAT')",
                paymentNo, memberId, "ORDER", orderId, paidOrder.get("payableAmount"));
        int payableCents = new BigDecimal(String.valueOf(paidOrder.get("payableAmount")))
                .movePointRight(2)
                .intValueExact();
        service.completeOrderPayment(paymentNo, "wx_test_" + suffix, payableCents);
        service.shipOrder(String.valueOf(paidOrder.get("orderNo")));
        service.confirmReceipt(memberId, String.valueOf(paidOrder.get("orderNo")));
        String afterSaleNo = service.createAfterSale(memberId, String.valueOf(paidOrder.get("orderNo")), "商品问题", "集成测试售后");
        service.rejectAfterSale(afterSaleNo);
        assertEquals("REJECTED", jdbc.queryForObject("select status from xy_after_sale where after_sale_no=?", String.class, afterSaleNo));

        Map<String, Object> refundedOrder = service.createOrder(memberId, orderInput);
        service.createOrderPayment(memberId, String.valueOf(refundedOrder.get("orderNo")), payService);
        String refundedAfterSaleNo = service.createAfterSale(memberId, String.valueOf(refundedOrder.get("orderNo")), "退款测试", "模拟支付退款");
        service.approveAfterSale(refundedAfterSaleNo, payService);
        assertEquals("APPROVED", jdbc.queryForObject("select status from xy_after_sale where after_sale_no=?", String.class, refundedAfterSaleNo));
        assertEquals("REFUNDED", jdbc.queryForObject("select status from xy_order where order_id=?", String.class, refundedOrder.get("orderId")));
        assertEquals("REFUNDED", jdbc.queryForObject("select status from xy_payment where business_type='ORDER' and business_id=?", String.class, refundedOrder.get("orderId")));
        assertEquals(9, jdbc.queryForObject("select stock from xy_product where product_id=?", Integer.class, productId));

        Map<String, Object> previousCode = service.issueMemberVerifyCode(memberId);
        assertEquals(10, ((Number) previousCode.get("expiresIn")).intValue());
        Map<String, Object> code = service.issueMemberVerifyCode(memberId);
        String verifyCode = String.valueOf(code.get("code"));
        assertThrows(ServiceException.class, () -> service.verifyMemberCode(String.valueOf(previousCode.get("code")), "integration-test"));
        assertEquals(memberId, ((Number) service.verifyMemberCode(verifyCode, "integration-test").get("memberId")).longValue());
        assertThrows(ServiceException.class, () -> service.verifyMemberCode(verifyCode, "integration-test"));
    }
}
