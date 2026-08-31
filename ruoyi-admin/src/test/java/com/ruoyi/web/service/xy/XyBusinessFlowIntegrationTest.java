package com.ruoyi.web.service.xy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.web.domain.xy.XyFinanceExportRow;

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
    private XyBenefitEventService benefitEventService;

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
        Map<String, Object> pauseInput = new HashMap<>();
        List<Long> pausedSlotIds = new ArrayList<>();
        pausedSlotIds.add(slotId);
        pauseInput.put("storeId", storeId);
        pauseInput.put("pauseDate", tomorrow.toString());
        pauseInput.put("slotIds", pausedSlotIds);
        pauseInput.put("announcement", "设备维护，当前时段暂停预约");
        Map<String, Object> savedPause = service.saveReservationPause(pauseInput, "integration-test");
        Map<String, Object> pausedAvailability = service.reservationAvailability(storeId, tomorrow);
        List<Map<String, Object>> pausedSlots = (List<Map<String, Object>>) pausedAvailability.get("slots");
        assertEquals(true, pausedSlots.get(0).get("paused"));
        assertEquals(false, pausedSlots.get(0).get("bookable"));
        assertEquals(1, ((List<?>) pausedAvailability.get("pauseAnnouncements")).size());
        assertThrows(ServiceException.class, () -> service.createReservation(memberId, storeId, slotId, seatId, tomorrow));
        assertTrue(service.adminReservationPauses().stream().anyMatch(row -> savedPause.get("batchNo").equals(row.get("batchNo"))));
        service.resumeReservationPause(String.valueOf(savedPause.get("batchNo")));
        assertEquals(false, ((List<Map<String, Object>>) service.reservationAvailability(storeId, tomorrow).get("slots")).get(0).get("paused"));

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
        List<XyFinanceExportRow> financeRows = service.financeExportRows();
        assertTrue(financeRows.stream().anyMatch(row -> paymentNo.equals(row.getPaymentNo())
                && new BigDecimal(String.valueOf(paidOrder.get("payableAmount"))).compareTo(row.getAmount()) == 0));
        MockHttpServletResponse financeResponse = new MockHttpServletResponse();
        new ExcelUtil<XyFinanceExportRow>(XyFinanceExportRow.class)
                .exportExcel(financeResponse, financeRows, "财务对账");
        assertTrue(financeResponse.getContentAsByteArray().length > 0);
        service.shipOrder(String.valueOf(paidOrder.get("orderNo")));
        service.confirmReceipt(memberId, String.valueOf(paidOrder.get("orderNo")));
        String afterSaleNo = service.createAfterSale(memberId, String.valueOf(paidOrder.get("orderNo")), "商品问题", "集成测试售后");
        service.rejectAfterSale(afterSaleNo);
        assertEquals("REJECTED", jdbc.queryForObject("select status from xy_after_sale where after_sale_no=?", String.class, afterSaleNo));

        Map<String, Object> refundedOrder = service.createOrder(memberId, orderInput);
        service.createOrderPayment(memberId, String.valueOf(refundedOrder.get("orderNo")), payService);
        String refundedAfterSaleNo = service.createAfterSale(memberId, String.valueOf(refundedOrder.get("orderNo")), "退款测试", "模拟支付退款");
        service.approveAfterSale(refundedAfterSaleNo, payService);
        assertEquals("RESTOCKED", jdbc.queryForObject("select status from xy_after_sale where after_sale_no=?", String.class, refundedAfterSaleNo));
        assertEquals("REFUNDED", jdbc.queryForObject("select status from xy_order where order_id=?", String.class, refundedOrder.get("orderId")));
        assertEquals("REFUNDED", jdbc.queryForObject("select status from xy_payment where business_type='ORDER' and business_id=?", String.class, refundedOrder.get("orderId")));
        assertEquals(9, jdbc.queryForObject("select stock from xy_product where product_id=?", Integer.class, productId));

        jdbc.update("update xy_membership_card set expire_date=curdate() where member_id=?", memberId);
        assertThrows(ServiceException.class, () -> service.createReservation(memberId, storeId, slotId, seatId, tomorrow));

        jdbc.update("update xy_reservation_slot set start_time='00:00:00',end_time='23:59:59' where slot_id=?", slotId);
        String todayReservationNo = "TR" + suffix.substring(0, 28);
        jdbc.update("insert into xy_reservation(reservation_no,member_id,store_id,slot_id,seat_id,reservation_date,verify_code) values(?,?,?,?,?,?,?)",
                todayReservationNo, memberId, storeId, slotId, seatId, LocalDate.now(), "98" + suffix.substring(0, 6));

        Map<String, Object> previousCode = service.issueMemberVerifyCode(memberId);
        assertEquals(10, ((Number) previousCode.get("expiresIn")).intValue());
        Map<String, Object> code = service.issueMemberVerifyCode(memberId);
        String verifyCode = String.valueOf(code.get("code"));
        assertEquals(4, verifyCode.length());
        assertThrows(ServiceException.class, () -> service.verifyMemberCode(String.valueOf(previousCode.get("code")), "integration-test"));
        Map<String, Object> verifiedMember = service.verifyMemberCode(verifyCode, "integration-test");
        assertEquals(memberId, ((Number) verifiedMember.get("memberId")).longValue());
        assertEquals(true, verifiedMember.get("reservationCheckedIn"));
        assertEquals("CHECKED_IN", jdbc.queryForObject("select status from xy_reservation where reservation_no=?", String.class, todayReservationNo));
        assertThrows(ServiceException.class, () -> service.verifyMemberCode(verifyCode, "integration-test"));

        Map<String, Object> manualMember = new HashMap<>();
        manualMember.put("nickname", "后台新建会员" + suffix.substring(0, 6));
        manualMember.put("mobile", "13900000000");
        manualMember.put("memberStatus", "0");
        Long manualMemberId = service.saveAdminMember(null, manualMember);
        manualMember.put("nickname", "后台修改会员" + suffix.substring(0, 6));
        service.saveAdminMember(manualMemberId, manualMember);
        assertEquals(String.valueOf(manualMember.get("nickname")), jdbc.queryForObject("select nickname from xy_member where member_id=?", String.class, manualMemberId));
        service.deleteAdminMember(manualMemberId);
        assertEquals(0, jdbc.queryForObject("select count(1) from xy_member where member_id=?", Integer.class, manualMemberId));
        assertThrows(ServiceException.class, () -> service.deleteAdminMember(memberId));
    }

    @Test
    void verifiedWechatMobileLinksPrecreatedMembershipWithoutDuplicateAccount()
    {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String mobile = "137" + String.format("%08d", Integer.toUnsignedLong(suffix.hashCode()) % 100000000L);
        jdbc.update("insert into xy_membership_plan(plan_name,amount,duration_days,daily_reservation_limit) values(?,?,?,?)",
                "手机号关联测试套餐" + suffix.substring(0, 6), new BigDecimal("88.00"), 30, 1);
        Long planId = jdbc.queryForObject("select last_insert_id()", Long.class);

        Map<String, Object> manual = new HashMap<>();
        manual.put("nickname", "门店预建会员" + suffix.substring(0, 5));
        manual.put("mobile", mobile);
        manual.put("memberStatus", "0");
        manual.put("grantMembership", true);
        manual.put("planId", planId);
        manual.put("membershipStartDate", LocalDate.now().toString());
        Long manualMemberId = service.saveAdminMember(null, manual);
        assertNotNull(service.currentCard(manualMemberId));

        Map<String, Object> login = service.loginByOpenId("phone_link_" + suffix, null);
        memberToken = String.valueOf(login.get("memberToken"));
        Long wechatMemberId = service.requireMember(memberToken);
        Map<String, Object> linked = service.bindVerifiedMobile(wechatMemberId, mobile);

        assertEquals(wechatMemberId, ((Number) linked.get("memberId")).longValue());
        assertEquals(1, jdbc.queryForObject("select count(1) from xy_member where member_id=? and mobile_verified_at is not null", Integer.class, wechatMemberId));
        assertNotNull(linked.get("card"));
        assertEquals(0, jdbc.queryForObject("select count(1) from xy_member where member_id=?", Integer.class, manualMemberId));
        assertEquals(1, jdbc.queryForObject("select count(1) from xy_member where mobile=?", Integer.class, mobile));
        assertEquals(wechatMemberId, jdbc.queryForObject(
                "select member_id from xy_membership_card where member_id=? order by card_id desc limit 1",
                Long.class, wechatMemberId));

        manual.put("grantMembership", false);
        Long reusedMemberId = service.saveAdminMember(null, manual);
        assertEquals(wechatMemberId, reusedMemberId);
        assertEquals(1, jdbc.queryForObject("select count(1) from xy_member where mobile=?", Integer.class, mobile));
    }

    @Test
    @SuppressWarnings("unchecked")
    void benefitEventSupportsPaidSeatMapPerEventLimitAndAdminFundsFlow()
    {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> login = service.loginByOpenId("benefit_" + suffix, null);
        memberToken = String.valueOf(login.get("memberToken"));
        Long memberId = service.requireMember(memberToken);
        String mobile = "136" + String.format("%08d", Integer.toUnsignedLong(suffix.hashCode()) % 100000000L);
        jdbc.update("update xy_member set mobile=? where member_id=?", mobile, memberId);
        jdbc.update("insert into xy_store(store_name,address,phone,business_hours) values(?,?,?,?)",
                "福利钓测试门店" + suffix.substring(0, 6), "测试地址", "13800000000", "10:00-22:15");
        Long storeId = jdbc.queryForObject("select last_insert_id()", Long.class);

        Map<String, Object> eventInput = new HashMap<>();
        eventInput.put("storeId", storeId);
        eventInput.put("eventDate", LocalDate.now().plusDays(1).toString());
        eventInput.put("announcement", "测试奖品：第一名礼品一份。人数不限，商家确认后正常开始。");
        eventInput.put("status", "OPEN");
        Map<String, Object> firstEvent = benefitEventService.saveEvent(null, eventInput, "integration-test");
        Long firstEventId = ((Number) firstEvent.get("eventId")).longValue();

        Map<String, Object> bookingInput = new HashMap<>();
        bookingInput.put("seatNo", 22);
        bookingInput.put("announcementVersion", firstEvent.get("announcementVersion"));
        bookingInput.put("announcementConfirmed", true);
        bookingInput.put("startNoticeAccepted", false);
        bookingInput.put("cancelNoticeAccepted", false);
        Map<String, Object> firstBooking = benefitEventService.createBookingPayment(memberId, firstEventId, bookingInput);
        assertEquals(true, firstBooking.get("paid"));
        assertEquals("BOOKED", jdbc.queryForObject(
                "select status from xy_benefit_booking where booking_no=?", String.class, firstBooking.get("bookingNo")));
        Map<String, Object> publicEvent = benefitEventService.publicEvent(firstEventId, memberId);
        List<Map<String, Object>> seats = (List<Map<String, Object>>) publicEvent.get("seats");
        assertEquals("UNAVAILABLE", seats.get(21).get("status"));
        bookingInput.put("seatNo", 21);
        assertThrows(ServiceException.class,
                () -> benefitEventService.createBookingPayment(memberId, firstEventId, bookingInput));

        eventInput.put("eventDate", LocalDate.now().plusDays(2).toString());
        Map<String, Object> secondEvent = benefitEventService.saveEvent(null, eventInput, "integration-test");
        bookingInput.put("announcementVersion", secondEvent.get("announcementVersion"));
        Map<String, Object> secondBooking = benefitEventService.createBookingPayment(memberId,
                ((Number) secondEvent.get("eventId")).longValue(), bookingInput);
        assertEquals(true, secondBooking.get("paid"));
        Long secondBookingId = jdbc.queryForObject(
                "select booking_id from xy_benefit_booking where booking_no=?", Long.class, secondBooking.get("bookingNo"));
        benefitEventService.refundBooking(secondBookingId, "集成测试单座处理", "integration-test");
        assertEquals("CLOSED", jdbc.queryForObject(
                "select status from xy_benefit_booking where booking_id=?", String.class, secondBookingId));
        assertEquals("REFUNDED", jdbc.queryForObject(
                "select status from xy_payment where business_type='BENEFIT_EVENT' and business_id=?", String.class, secondBookingId));

        eventInput.put("eventDate", LocalDate.now().plusDays(3).toString());
        Map<String, Object> thirdEvent = benefitEventService.saveEvent(null, eventInput, "integration-test");
        bookingInput.put("announcementVersion", thirdEvent.get("announcementVersion"));
        Map<String, Object> thirdBooking = benefitEventService.createBookingPayment(memberId,
                ((Number) thirdEvent.get("eventId")).longValue(), bookingInput);
        Long thirdBookingId = jdbc.queryForObject(
                "select booking_id from xy_benefit_booking where booking_no=?", Long.class, thirdBooking.get("bookingNo"));
        benefitEventService.cancelEvent(((Number) thirdEvent.get("eventId")).longValue(), "测试整场取消", "integration-test");
        assertEquals("CANCELED", jdbc.queryForObject(
                "select status from xy_benefit_event where event_id=?", String.class, thirdEvent.get("eventId")));
        assertEquals("CLOSED", jdbc.queryForObject(
                "select status from xy_benefit_booking where booking_id=?", String.class, thirdBookingId));
        assertEquals("专场已取消", benefitEventService.memberBookings(memberId).stream()
                .filter(row -> thirdBooking.get("bookingNo").equals(row.get("bookingNo")))
                .findFirst().orElseThrow().get("displayStatus"));
    }
}
