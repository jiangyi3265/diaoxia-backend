package com.ruoyi.web.service.xy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.exception.ServiceException;

/** 无微信商户配置时，线下收款/退款不得自动标记成功。 */
@SpringBootTest(
        classes = RuoYiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "TOKEN_SECRET=integration-test-token-secret-at-least-32-bytes",
                "xy.wechat-pay.demo-enabled=false",
                "xy.wechat-pay.mch-id=",
                "xy.order-expire-minutes=30"
        })
@EnabledIfEnvironmentVariable(named = "RUN_XY_INTEGRATION_TESTS", matches = "true")
@Transactional
class XyOfflinePaymentIntegrationTest
{
    @Autowired private XyBusinessService service;
    @Autowired private XyWechatPayService payService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void offlineOrderRequiresExplicitConfirmationAndIsIdempotent()
    {
        Fixture fixture = fixture();
        int initialStock = stock(fixture.productId);
        Map<String,Object> order = service.createOrder(fixture.memberId, orderInput(fixture.productId));
        String orderNo = String.valueOf(order.get("orderNo"));
        assertEquals(initialStock - 2, stock(fixture.productId));

        Map<String,Object> first = service.createOrderPayment(fixture.memberId, orderNo, payService);
        Map<String,Object> second = service.createOrderPayment(fixture.memberId, orderNo, payService);
        assertTrue(Boolean.TRUE.equals(first.get("pendingConfirmation")));
        assertFalse(Boolean.TRUE.equals(first.get("paid")));
        assertTrue(Boolean.TRUE.equals(first.get("expiresAutomatically")));
        assertTrue(((Number) first.get("expiresInMinutes")).longValue() > 0);
        assertTrue(first.get("expireTime") != null);
        Map<String,Object> detail = service.orderDetail(fixture.memberId, orderNo);
        assertEquals(first.get("expireTime"), detail.get("paymentExpireTime"));
        Map<String,Object> pendingBill = service.memberBills(fixture.memberId).stream()
                .filter(row -> first.get("paymentNo").equals(row.get("paymentNo")))
                .findFirst().orElseThrow();
        assertEquals(first.get("expireTime"), pendingBill.get("expireTime"));
        assertEquals(first.get("paymentNo"), second.get("paymentNo"));
        assertEquals(1, jdbc.queryForObject("select count(1) from xy_payment where business_type='ORDER' and business_id=?", Integer.class, order.get("orderId")));
        assertEquals("PENDING_PAYMENT", jdbc.queryForObject("select status from xy_order where order_id=?", String.class, order.get("orderId")));
        assertThrows(ServiceException.class, () -> service.completeWechatPayment(String.valueOf(first.get("paymentNo")),
                "wx-wrong-channel", new BigDecimal(String.valueOf(order.get("payableAmount"))).movePointRight(2).intValueExact()));

        jdbc.update("update xy_order set create_time=date_sub(now(),interval 31 minute) where order_id=?", order.get("orderId"));
        service.maintainExpiredBusinessRecords();
        assertEquals("PENDING_PAYMENT", jdbc.queryForObject("select status from xy_order where order_id=?", String.class, order.get("orderId")));

        String paymentNo = String.valueOf(first.get("paymentNo"));
        service.confirmOfflinePayment(paymentNo, "cashier-a");
        service.confirmOfflinePayment(paymentNo, "cashier-a");
        assertEquals("PAID", jdbc.queryForObject("select status from xy_order where order_id=?", String.class, order.get("orderId")));
        assertEquals("SUCCESS", jdbc.queryForObject("select status from xy_payment where payment_no=?", String.class, paymentNo));
        assertThrows(ServiceException.class, () -> service.closeOfflinePayment(paymentNo, "cashier-a"));
    }

    @Test
    void closeOfflineOrderReturnsStockAndIsIdempotent()
    {
        Fixture fixture = fixture();
        int initialStock = stock(fixture.productId);
        Map<String,Object> expiredOrder = service.createOrder(fixture.memberId, orderInput(fixture.productId));
        Map<String,Object> expiredPayment = service.createOrderPayment(fixture.memberId, String.valueOf(expiredOrder.get("orderNo")), payService);
        jdbc.update("update xy_payment set create_time=date_sub(now(),interval 25 hour) where payment_no=?", expiredPayment.get("paymentNo"));
        service.maintainExpiredBusinessRecords();
        assertEquals("CANCELED", jdbc.queryForObject("select status from xy_order where order_id=?", String.class, expiredOrder.get("orderId")));
        assertEquals("CLOSED", jdbc.queryForObject("select status from xy_payment where payment_no=?", String.class, expiredPayment.get("paymentNo")));
        assertEquals(initialStock, stock(fixture.productId));

        Map<String,Object> order = service.createOrder(fixture.memberId, orderInput(fixture.productId));
        Map<String,Object> payment = service.createOrderPayment(fixture.memberId, String.valueOf(order.get("orderNo")), payService);
        String paymentNo = String.valueOf(payment.get("paymentNo"));

        service.closeOfflinePayment(paymentNo, "cashier-b");
        service.closeOfflinePayment(paymentNo, "cashier-b");
        assertEquals(initialStock, stock(fixture.productId));
        assertEquals("CANCELED", jdbc.queryForObject("select status from xy_order where order_id=?", String.class, order.get("orderId")));
        assertEquals("CLOSED", jdbc.queryForObject("select status from xy_payment where payment_no=?", String.class, paymentNo));
    }

    @Test
    void offlineMembershipAndRefundBothRequireExplicitCompletion()
    {
        Fixture fixture = fixture();
        Map<String,Object> first = service.createMembershipPayment(fixture.memberId, fixture.planId, payService);
        Map<String,Object> second = service.createMembershipPayment(fixture.memberId, fixture.planId, payService);
        assertEquals(first.get("paymentNo"), second.get("paymentNo"));
        assertEquals(0, jdbc.queryForObject("select count(1) from xy_membership_card where member_id=?", Integer.class, fixture.memberId));
        jdbc.update("update xy_membership_order set create_time=date_sub(now(),interval 31 minute) where order_no=?", first.get("orderNo"));
        service.maintainExpiredBusinessRecords();
        assertEquals("PENDING_PAYMENT", jdbc.queryForObject("select status from xy_membership_order where order_no=?", String.class, first.get("orderNo")));
        service.confirmOfflinePayment(String.valueOf(first.get("paymentNo")), "cashier-c");
        assertEquals(1, jdbc.queryForObject("select count(1) from xy_membership_card where member_id=?", Integer.class, fixture.memberId));

        Map<String,Object> membershipExpired = service.createMembershipPayment(fixture.memberId, fixture.planId, payService);
        jdbc.update("update xy_payment set create_time=date_sub(now(),interval 25 hour) where payment_no=?", membershipExpired.get("paymentNo"));
        service.maintainExpiredBusinessRecords();
        assertEquals("CANCELED", jdbc.queryForObject("select status from xy_membership_order where order_no=?", String.class, membershipExpired.get("orderNo")));
        assertEquals("CLOSED", jdbc.queryForObject("select status from xy_payment where payment_no=?", String.class, membershipExpired.get("paymentNo")));

        Map<String,Object> membershipToClose = service.createMembershipPayment(fixture.memberId, fixture.planId, payService);
        service.closeOfflinePayment(String.valueOf(membershipToClose.get("paymentNo")), "cashier-c");
        service.closeOfflinePayment(String.valueOf(membershipToClose.get("paymentNo")), "cashier-c");
        assertEquals("CANCELED", jdbc.queryForObject("select status from xy_membership_order where order_no=?", String.class, membershipToClose.get("orderNo")));
        assertEquals(1, jdbc.queryForObject("select count(1) from xy_membership_card where member_id=?", Integer.class, fixture.memberId));

        int initialStock = stock(fixture.productId);
        Map<String,Object> order = service.createOrder(fixture.memberId, orderInput(fixture.productId));
        Map<String,Object> payment = service.createOrderPayment(fixture.memberId, String.valueOf(order.get("orderNo")), payService);
        service.confirmOfflinePayment(String.valueOf(payment.get("paymentNo")), "cashier-c");
        String afterSaleNo = service.createAfterSale(fixture.memberId, String.valueOf(order.get("orderNo")), "退款测试", null);
        service.approveAfterSale(afterSaleNo, payService);
        assertEquals("REFUNDING", jdbc.queryForObject("select status from xy_after_sale where after_sale_no=?", String.class, afterSaleNo));
        assertEquals(initialStock - 2, stock(fixture.productId));
        service.completeOfflineRefund(afterSaleNo);
        service.completeOfflineRefund(afterSaleNo);
        assertEquals("RESTOCKED", jdbc.queryForObject("select status from xy_after_sale where after_sale_no=?", String.class, afterSaleNo));
        assertEquals("REFUNDED", jdbc.queryForObject("select status from xy_order where order_id=?", String.class, order.get("orderId")));
        assertEquals(initialStock, stock(fixture.productId));
    }

    @Test
    void eachMemberCanHoldAtMostThreePendingProductOrders()
    {
        Fixture fixture = fixture();
        Map<String,Object> first = service.createOrder(fixture.memberId, orderInput(fixture.productId));
        service.createOrder(fixture.memberId, orderInput(fixture.productId));
        service.createOrder(fixture.memberId, orderInput(fixture.productId));
        assertThrows(ServiceException.class, () -> service.createOrder(fixture.memberId, orderInput(fixture.productId)));
        service.cancelOrder(fixture.memberId, String.valueOf(first.get("orderNo")));
        service.createOrder(fixture.memberId, orderInput(fixture.productId));
        assertEquals(3, jdbc.queryForObject("select count(1) from xy_order where member_id=? and status='PENDING_PAYMENT'", Integer.class, fixture.memberId));
    }

    @Test
    void shippedAndCompletedRefundsRequireExplicitReturnedGoodsRestock()
    {
        Fixture fixture = fixture();
        assertDeliveredRefundRequiresRestock(fixture, false);
        assertDeliveredRefundRequiresRestock(fixture, true);
    }

    private void assertDeliveredRefundRequiresRestock(Fixture fixture, boolean completeDelivery)
    {
        int initialStock = stock(fixture.productId);
        Map<String,Object> order = service.createOrder(fixture.memberId, orderInput(fixture.productId));
        Map<String,Object> payment = service.createOrderPayment(fixture.memberId, String.valueOf(order.get("orderNo")), payService);
        service.confirmOfflinePayment(String.valueOf(payment.get("paymentNo")), "cashier-return");
        service.shipOrder(String.valueOf(order.get("orderNo")));
        if (completeDelivery) service.confirmReceipt(fixture.memberId, String.valueOf(order.get("orderNo")));
        String afterSaleNo = service.createAfterSale(fixture.memberId, String.valueOf(order.get("orderNo")), "退货测试", null);
        service.approveAfterSale(afterSaleNo, payService);
        service.completeOfflineRefund(afterSaleNo);
        assertEquals("APPROVED", jdbc.queryForObject("select status from xy_after_sale where after_sale_no=?", String.class, afterSaleNo));
        assertEquals(initialStock - 2, stock(fixture.productId));
        service.restockReturnedAfterSale(afterSaleNo);
        service.restockReturnedAfterSale(afterSaleNo);
        assertEquals("RESTOCKED", jdbc.queryForObject("select status from xy_after_sale where after_sale_no=?", String.class, afterSaleNo));
        assertEquals(initialStock, stock(fixture.productId));
    }

    @Test
    void storeCoordinatesMustBeCompleteAndWithinRange()
    {
        Map<String,Object> store = new HashMap<>();
        store.put("storeName", "坐标测试门店");
        store.put("address", "测试地址");
        store.put("phone", "13800000000");
        store.put("businessHours", "09:00-22:00");
        store.put("longitude", "113.9345280");
        assertThrows(ServiceException.class, () -> service.saveStore(store));
        store.put("latitude", "91");
        assertThrows(ServiceException.class, () -> service.saveStore(store));
        store.put("latitude", "22.5405030");
        Long storeId = service.saveStore(store);
        assertEquals(new BigDecimal("113.9345280"), jdbc.queryForObject("select longitude from xy_store where store_id=?", BigDecimal.class, storeId));

        Map<String,Object> invalidSeat = new HashMap<>();
        invalidSeat.put("storeId", Long.MAX_VALUE);
        invalidSeat.put("seatCode", "A01");
        assertThrows(ServiceException.class, () -> service.saveSeat(invalidSeat));

        Map<String,Object> invalidProduct = new HashMap<>();
        invalidProduct.put("productName", "非法金额商品");
        invalidProduct.put("categoryName", "测试");
        invalidProduct.put("salePrice", "1.001");
        invalidProduct.put("stock", 1);
        assertThrows(ServiceException.class, () -> service.saveProduct(invalidProduct));
        invalidProduct.put("salePrice", "1.00");
        invalidProduct.put("sortOrder", -1);
        assertThrows(ServiceException.class, () -> service.saveProduct(invalidProduct));
    }

    private Fixture fixture()
    {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("insert into xy_member(openid,invite_code,nickname) values(?,?,?)",
                "offline_" + suffix, suffix.substring(0, 6), "线下测试会员");
        Long memberId = jdbc.queryForObject("select last_insert_id()", Long.class);
        jdbc.update("insert into xy_membership_plan(plan_name,amount,duration_days,daily_reservation_limit,status) values(?,?,30,1,'0')",
                "线下测试套餐" + suffix, new BigDecimal("128.00"));
        Long planId = jdbc.queryForObject("select last_insert_id()", Long.class);
        jdbc.update("insert into xy_product(product_name,category_name,sale_price,stock,status) values(?,?,?,20,'0')",
                "线下测试商品" + suffix, "测试", new BigDecimal("18.00"));
        Long productId = jdbc.queryForObject("select last_insert_id()", Long.class);
        return new Fixture(memberId, planId, productId);
    }

    private Map<String,Object> orderInput(Long productId)
    {
        Map<String,Object> input = new HashMap<>();
        input.put("productId", productId);
        input.put("quantity", 2);
        input.put("deliveryType", "PICKUP");
        return input;
    }

    private int stock(Long productId)
    {
        return jdbc.queryForObject("select stock from xy_product where product_id=?", Integer.class, productId);
    }

    private static final class Fixture
    {
        final Long memberId;
        final Long planId;
        final Long productId;

        Fixture(Long memberId, Long planId, Long productId)
        {
            this.memberId = memberId;
            this.planId = planId;
            this.productId = productId;
        }
    }
}
