package com.ruoyi.web.service.xy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;

/**
 * 福利钓专场独立业务。普通会员预约仍由 {@link XyBusinessService} 处理，两个流程不共享座位锁和资格规则。
 */
@Service
public class XyBenefitEventService
{
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalTime START_TIME = LocalTime.of(20, 15);
    private static final LocalTime END_TIME = LocalTime.of(22, 15);
    private static final LocalTime SIGNUP_DEADLINE = LocalTime.of(19, 30);
    private static final BigDecimal FEE = new BigDecimal("100.00");
    private static final int SEAT_COUNT = 22;
    private static final int HOLD_MINUTES = 5;
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final XyWechatPayService payService;
    private final XyWechatService wechatService;

    public XyBenefitEventService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
            XyWechatPayService payService, XyWechatService wechatService)
    {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.payService = payService;
        this.wechatService = wechatService;
    }

    @Scheduled(fixedDelayString = "${xy.maintenance-interval-ms:60000}")
    public void maintainBenefitRecords()
    {
        expirePendingBookings();
        LocalDateTime now = now();
        jdbcTemplate.update(
                "update xy_benefit_event set status='FINISHED' where status in('OPEN','CONFIRMED') and timestamp(event_date,end_time)<?",
                Timestamp.valueOf(now));
        List<Long> canceledBookings = jdbcTemplate.queryForList(
                "select b.booking_id from xy_benefit_booking b join xy_benefit_event e on e.event_id=b.event_id "
                        + "where e.status='CANCELED' and b.status='BOOKED' order by b.booking_id limit 50",
                Long.class);
        for (Long bookingId : canceledBookings)
        {
            try { initiateBookingRefund(bookingId, "福利钓专场取消", "system"); }
            catch (RuntimeException ignored) { /* 失败状态会保留在后台，下一轮或管理员可重试。 */ }
        }
        dispatchDueNotices();
    }

    public List<Map<String, Object>> publicEvents(Long memberId)
    {
        expirePendingBookings();
        LocalDate today = today();
        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "select e.event_id as eventId,e.event_no as eventNo,e.store_id as storeId,e.event_date as eventDate,"
                        + "date_format(e.start_time,'%H:%i') as startTime,date_format(e.end_time,'%H:%i') as endTime,"
                        + "date_format(e.signup_deadline,'%H:%i') as signupDeadline,e.fee_amount as feeAmount,"
                        + "e.announcement,e.announcement_version as announcementVersion,e.status,st.store_name as storeName,"
                        + "(select count(1) from xy_benefit_booking b where b.event_id=e.event_id and b.seat_lock=1) as bookedCount "
                        + "from xy_benefit_event e join xy_store st on st.store_id=e.store_id and st.status='0' "
                        + "where e.event_date between ? and ? and e.status<>'DRAFT' order by e.event_date,e.event_id",
                today, today.plusDays(6));
        for (Map<String, Object> event : events)
        {
            enrichPublicEvent(event, memberId);
        }
        return events;
    }

    public Map<String, Object> publicEvent(Long eventId, Long memberId)
    {
        expirePendingBookings();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select e.event_id as eventId,e.event_no as eventNo,e.store_id as storeId,e.event_date as eventDate,"
                        + "date_format(e.start_time,'%H:%i') as startTime,date_format(e.end_time,'%H:%i') as endTime,"
                        + "date_format(e.signup_deadline,'%H:%i') as signupDeadline,e.fee_amount as feeAmount,"
                        + "e.announcement,e.announcement_version as announcementVersion,e.status,e.cancel_reason as cancelReason,"
                        + "st.store_name as storeName,st.address,st.phone from xy_benefit_event e "
                        + "join xy_store st on st.store_id=e.store_id where e.event_id=? and e.status<>'DRAFT'",
                eventId);
        if (rows.isEmpty()) throw new ServiceException("福利钓专场不存在或尚未开放");
        Map<String, Object> event = rows.get(0);
        enrichPublicEvent(event, memberId);
        Map<Integer, String> occupied = new HashMap<>();
        List<Map<String, Object>> bookings = jdbcTemplate.queryForList(
                "select seat_no as seatNo,status from xy_benefit_booking where event_id=? and seat_lock=1",
                eventId);
        for (Map<String, Object> booking : bookings)
            occupied.put(((Number) booking.get("seatNo")).intValue(), "UNAVAILABLE");
        List<Map<String, Object>> seats = new ArrayList<>();
        for (int seatNo = 1; seatNo <= SEAT_COUNT; seatNo++)
        {
            Map<String, Object> seat = new LinkedHashMap<>();
            seat.put("seatNo", seatNo);
            seat.put("status", occupied.getOrDefault(seatNo, "AVAILABLE"));
            seats.add(seat);
        }
        event.put("seats", seats);
        return event;
    }

    private void enrichPublicEvent(Map<String, Object> event, Long memberId)
    {
        LocalDate date = asDate(event.get("eventDate"));
        LocalTime deadline = LocalTime.parse(String.valueOf(event.get("signupDeadline")));
        String status = String.valueOf(event.get("status"));
        boolean beforeDeadline = date.isAfter(today()) || (date.equals(today()) && currentTime().isBefore(deadline));
        int bookedCount = ((Number) event.getOrDefault("bookedCount", 0)).intValue();
        event.put("seatCount", SEAT_COUNT);
        event.put("remainingCount", Math.max(0, SEAT_COUNT - bookedCount));
        event.put("signupOpen", ("OPEN".equals(status) || "CONFIRMED".equals(status))
                && beforeDeadline && bookedCount < SEAT_COUNT);
        event.put("displayStatus", displayEventStatus(status, beforeDeadline));
        if (memberId != null)
        {
            List<Map<String, Object>> mine = jdbcTemplate.queryForList(
                    "select booking_no as bookingNo,seat_no as seatNo,status from xy_benefit_booking "
                            + "where event_id=? and member_id=? order by booking_id desc limit 1",
                    event.get("eventId"), memberId);
            if (!mine.isEmpty())
            {
                Map<String, Object> booking = mine.get(0);
                event.put("myBookingNo", booking.get("bookingNo"));
                event.put("mySeatNo", booking.get("seatNo"));
                event.put("myBookingStatus", displayBookingStatus(String.valueOf(booking.get("status")), status));
            }
        }
    }

    public List<Map<String, Object>> memberBookings(Long memberId)
    {
        expirePendingBookings();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select b.booking_no as bookingNo,b.seat_no as seatNo,b.status,e.event_date as eventDate,"
                        + "date_format(e.start_time,'%H:%i') as startTime,date_format(e.end_time,'%H:%i') as endTime,"
                        + "e.status as eventStatus,e.announcement as announcement,st.store_name as storeName,st.address,st.phone "
                        + "from xy_benefit_booking b join xy_benefit_event e on e.event_id=b.event_id "
                        + "join xy_store st on st.store_id=e.store_id where b.member_id=? "
                        + "order by e.event_date desc,b.booking_id desc",
                memberId);
        for (Map<String, Object> row : rows)
        {
            row.put("displayStatus", displayBookingStatus(String.valueOf(row.get("status")),
                    String.valueOf(row.get("eventStatus"))));
        }
        return rows;
    }

    @Transactional
    public Map<String, Object> createBookingPayment(Long memberId, Long eventId, Map<String, Object> body)
    {
        expirePendingBookings();
        int seatNo = intValue(body.get("seatNo"), "请选择座位");
        if (seatNo < 1 || seatNo > SEAT_COUNT) throw new ServiceException("座位号必须在1至22之间");
        if (!booleanValue(body.get("announcementConfirmed"))) throw new ServiceException("请先阅读并确认本场公告");

        List<Map<String, Object>> members = jdbcTemplate.queryForList(
                "select member_id,openid,mobile from xy_member where member_id=? and status='0' for update", memberId);
        if (members.isEmpty()) throw new ServiceException("用户状态异常，请重新登录");
        Map<String, Object> member = members.get(0);
        String mobile = string(member.get("mobile"));
        if (!mobile.matches("^1\\d{10}$")) throw new ServiceException("请先授权微信手机号后再报名");

        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "select event_id,event_no,event_date,start_time,end_time,signup_deadline,fee_amount,announcement,"
                        + "announcement_version,status from xy_benefit_event where event_id=? for update", eventId);
        if (events.isEmpty()) throw new ServiceException("福利钓专场不存在");
        Map<String, Object> event = events.get(0);
        String eventStatus = string(event.get("status"));
        if (!("OPEN".equals(eventStatus) || "CONFIRMED".equals(eventStatus)))
            throw new ServiceException("本场福利钓专场当前不能报名");
        LocalDate eventDate = asDate(event.get("event_date"));
        LocalTime deadline = asTime(event.get("signup_deadline"));
        if (eventDate.isBefore(today()) || (eventDate.equals(today()) && !currentTime().isBefore(deadline)))
            throw new ServiceException("本场已于19:30截止报名");
        int announcementVersion = intValue(body.get("announcementVersion"), "公告版本缺失，请刷新后重试");
        if (announcementVersion != ((Number) event.get("announcement_version")).intValue())
            throw new ServiceException("公告内容已更新，请重新阅读并确认");

        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "select b.booking_id,b.booking_no,b.seat_no,b.status,b.expires_time,b.payment_payload "
                        + "from xy_benefit_booking b where b.event_id=? and b.member_id=? and b.member_lock=1 for update",
                eventId, memberId);
        if (!existing.isEmpty())
        {
            Map<String, Object> current = existing.get(0);
            if ("BOOKED".equals(string(current.get("status"))))
                throw new ServiceException("你已报名本场福利钓专场，每人每场只能报名一个座位");
            Timestamp expires = (Timestamp) current.get("expires_time");
            if ("PENDING_PAYMENT".equals(string(current.get("status"))) && expires != null
                    && expires.toLocalDateTime().isAfter(now())
                    && ((Number) current.get("seat_no")).intValue() == seatNo
                    && !StringUtils.isEmpty(string(current.get("payment_payload"))))
            {
                return paymentPayload(string(current.get("payment_payload")), string(current.get("booking_no")), eventId, seatNo);
            }
            throw new ServiceException("本场已有一个待确认座位，请在5分钟后刷新重选");
        }

        String bookingNo = nextNo("FB");
        Timestamp expiresTime = Timestamp.valueOf(now().plusMinutes(HOLD_MINUTES));
        try
        {
            jdbcTemplate.update(
                    "insert into xy_benefit_booking(booking_no,event_id,member_id,seat_no,announcement_version,"
                            + "announcement_snapshot,announcement_confirmed_time,start_notice_accepted,cancel_notice_accepted,expires_time) "
                            + "values(?,?,?,?,?,?,now(),?,?,?)",
                    bookingNo, eventId, memberId, seatNo, announcementVersion, event.get("announcement"),
                    booleanValue(body.get("startNoticeAccepted")) ? 1 : 0,
                    booleanValue(body.get("cancelNoticeAccepted")) ? 1 : 0, expiresTime);
        }
        catch (DuplicateKeyException ex)
        {
            throw new ServiceException("该座位刚刚被其他用户选择，或你已报名本场，请刷新后重试");
        }
        Long bookingId = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        String paymentNo = nextNo("FP");
        String channel = payService.isDemoEnabled() ? "DEMO" : "WECHAT";
        if (!payService.isDemoEnabled() && !payService.isWechatPayConfigured())
            throw new ServiceException("福利钓专场当前未开放微信支付");
        jdbcTemplate.update(
                "insert into xy_payment(payment_no,member_id,business_type,business_id,amount,channel) values(?,?,?,?,?,?)",
                paymentNo, memberId, "BENEFIT_EVENT", bookingId, FEE, channel);

        Map<String, Object> result = new LinkedHashMap<>();
        if (payService.isDemoEnabled())
        {
            completeBenefitPayment(paymentNo, "DEMO-" + nextNo("TX"), 10000, null);
            result.put("demoPayment", true);
            result.put("paid", true);
        }
        else
        {
            Map<String, Object> payload = payService.jsapi(paymentNo, string(member.get("openid")), 10000,
                    "福利钓专场 " + eventDate);
            result.putAll(payload);
            try
            {
                jdbcTemplate.update("update xy_benefit_booking set payment_payload=? where booking_id=?",
                        objectMapper.writeValueAsString(payload), bookingId);
            }
            catch (Exception ex) { throw new ServiceException("支付参数保存失败，请重新报名"); }
        }
        result.put("bookingNo", bookingNo);
        result.put("eventId", eventId);
        result.put("seatNo", seatNo);
        result.put("holdMinutes", HOLD_MINUTES);
        return result;
    }

    private Map<String, Object> paymentPayload(String json, String bookingNo, Long eventId, int seatNo)
    {
        try
        {
            Map<String, Object> result = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            result.put("bookingNo", bookingNo);
            result.put("eventId", eventId);
            result.put("seatNo", seatNo);
            result.put("holdMinutes", HOLD_MINUTES);
            return result;
        }
        catch (Exception ex) { throw new ServiceException("待支付信息已失效，请稍后刷新重试"); }
    }

    @Transactional
    public boolean completeWechatPaymentIfApplicable(String paymentNo, String transactionId, Integer totalFen)
    {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select payment_id,business_id,amount,channel,status,transaction_id from xy_payment "
                        + "where payment_no=? and business_type='BENEFIT_EVENT' for update", paymentNo);
        if (rows.isEmpty()) return false;
        completeBenefitPayment(paymentNo, transactionId, totalFen, "WECHAT");
        return true;
    }

    private void completeBenefitPayment(String paymentNo, String transactionId, Integer totalFen, String expectedChannel)
    {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select payment_id,business_id,amount,channel,status,transaction_id from xy_payment "
                        + "where payment_no=? and business_type='BENEFIT_EVENT' for update", paymentNo);
        if (rows.isEmpty()) throw new ServiceException("福利钓支付单不存在");
        Map<String, Object> payment = rows.get(0);
        if (expectedChannel != null && !expectedChannel.equals(string(payment.get("channel"))))
            throw new ServiceException("支付回调渠道不匹配");
        if (totalFen == null || cents(payment.get("amount")) != totalFen)
            throw new ServiceException("支付金额校验失败");
        if (StringUtils.isEmpty(transactionId)) throw new ServiceException("支付交易号不能为空");
        if ("SUCCESS".equals(string(payment.get("status"))))
        {
            if (!transactionId.equals(string(payment.get("transaction_id"))))
                throw new ServiceException("支付交易号与已入账记录不一致");
            return;
        }
        if (!"PENDING".equals(string(payment.get("status")))) throw new ServiceException("支付单状态异常");
        Long bookingId = ((Number) payment.get("business_id")).longValue();
        int updated = jdbcTemplate.update(
                "update xy_benefit_booking set status='BOOKED',expires_time=null,payment_payload=null,booked_time=now() "
                        + "where booking_id=? and status='PENDING_PAYMENT'", bookingId);
        if (updated != 1) throw new ServiceException("报名状态异常，无法完成支付");
        jdbcTemplate.update(
                "update xy_payment set status='SUCCESS',transaction_id=?,paid_time=now() where payment_no=?",
                transactionId, paymentNo);
    }

    public List<Map<String, Object>> adminEvents()
    {
        expirePendingBookings();
        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "select e.event_id as eventId,e.event_no as eventNo,e.store_id as storeId,e.event_date as eventDate,"
                        + "date_format(e.start_time,'%H:%i') as startTime,date_format(e.end_time,'%H:%i') as endTime,"
                        + "date_format(e.signup_deadline,'%H:%i') as signupDeadline,e.fee_amount as feeAmount,"
                        + "e.announcement,e.announcement_version as announcementVersion,e.status,e.cancel_reason as cancelReason,"
                        + "e.confirmed_time as confirmedTime,e.canceled_time as canceledTime,st.store_name as storeName,"
                        + "sum(case when b.status='BOOKED' then 1 else 0 end) as bookedCount,"
                        + "sum(case when b.status='PENDING_PAYMENT' then 1 else 0 end) as pendingCount,"
                        + "sum(case when r.status='FAILED' then 1 else 0 end) as failedCount "
                        + "from xy_benefit_event e join xy_store st on st.store_id=e.store_id "
                        + "left join xy_benefit_booking b on b.event_id=e.event_id "
                        + "left join xy_benefit_refund r on r.booking_id=b.booking_id "
                        + "where e.event_date>=date_sub(curdate(),interval 7 day) "
                        + "group by e.event_id order by e.event_date desc,e.event_id desc");
        for (Map<String, Object> event : events)
            event.put("remainingCount", SEAT_COUNT - ((Number) event.get("bookedCount")).intValue()
                    - ((Number) event.get("pendingCount")).intValue());
        return events;
    }

    public Map<String, Object> adminEvent(Long eventId)
    {
        List<Map<String, Object>> events = adminEvents();
        Map<String, Object> event = events.stream()
                .filter(row -> ((Number) row.get("eventId")).longValue() == eventId.longValue())
                .findFirst().orElseThrow(() -> new ServiceException("福利钓专场不存在"));
        event.put("bookings", jdbcTemplate.queryForList(
                "select b.booking_id as bookingId,b.booking_no as bookingNo,b.seat_no as seatNo,b.status,"
                        + "b.booked_time as bookedTime,b.expires_time as expiresTime,m.nickname,m.mobile,"
                        + "p.payment_no as paymentNo,p.amount,p.channel,p.status as paymentStatus,p.transaction_id as transactionId,"
                        + "r.refund_no as refundNo,r.refund_id as refundId,r.status as refundStatus,r.reason as refundReason "
                        + "from xy_benefit_booking b join xy_member m on m.member_id=b.member_id "
                        + "join xy_payment p on p.business_type='BENEFIT_EVENT' and p.business_id=b.booking_id "
                        + "left join xy_benefit_refund r on r.booking_id=b.booking_id where b.event_id=? "
                        + "order by b.seat_no,b.booking_id desc", eventId));
        return event;
    }

    @Transactional
    public Map<String, Object> saveEvent(Long eventId, Map<String, Object> body, String operator)
    {
        Long storeId = longValue(body.get("storeId"), "请选择门店");
        LocalDate date;
        try { date = LocalDate.parse(string(body.get("eventDate"))); }
        catch (Exception ex) { throw new ServiceException("请选择正确的场次日期"); }
        if (date.isBefore(today()) || date.isAfter(today().plusDays(6)))
            throw new ServiceException("只能创建未来7天内的福利钓专场");
        if (date.equals(today()) && !currentTime().isBefore(SIGNUP_DEADLINE))
            throw new ServiceException("当天已超过19:30，不能再创建或开放场次");
        Integer storeExists = jdbcTemplate.queryForObject(
                "select count(1) from xy_store where store_id=? and status='0'", Integer.class, storeId);
        if (storeExists == null || storeExists == 0) throw new ServiceException("门店不存在或已停用");
        String announcement = string(body.get("announcement")).trim();
        if (announcement.length() < 10 || announcement.length() > 2000)
            throw new ServiceException("公告需填写10至2000个字，包含奖品及开闭场条件");
        String requestedStatus = "OPEN".equalsIgnoreCase(string(body.get("status"))) ? "OPEN" : "DRAFT";

        if (eventId == null)
        {
            try
            {
                jdbcTemplate.update(
                        "insert into xy_benefit_event(event_no,store_id,event_date,start_time,end_time,signup_deadline,"
                                + "fee_amount,announcement,status,create_by,update_by) values(?,?,?,'20:15:00','22:15:00',"
                                + "'19:30:00',100.00,?,?,?,?)",
                        nextNo("FE"), storeId, date, announcement, requestedStatus, operator, operator);
            }
            catch (DuplicateKeyException ex) { throw new ServiceException("该门店当天已经创建福利钓专场"); }
            eventId = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        }
        else
        {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "select event_id,announcement,status from xy_benefit_event where event_id=? for update", eventId);
            if (rows.isEmpty()) throw new ServiceException("福利钓专场不存在");
            String oldStatus = string(rows.get(0).get("status"));
            if ("CANCELED".equals(oldStatus) || "FINISHED".equals(oldStatus))
                throw new ServiceException("已取消或已结束的场次不能修改");
            boolean changed = !Objects.equals(announcement, string(rows.get(0).get("announcement")));
            try
            {
                jdbcTemplate.update(
                        "update xy_benefit_event set store_id=?,event_date=?,announcement=?,"
                                + "announcement_version=announcement_version+?,status=?,update_by=? where event_id=?",
                        storeId, date, announcement, changed ? 1 : 0,
                        "CONFIRMED".equals(oldStatus) ? oldStatus : requestedStatus, operator, eventId);
            }
            catch (DuplicateKeyException ex) { throw new ServiceException("该门店当天已经创建福利钓专场"); }
        }
        return adminEvent(eventId);
    }

    public Map<String, Object> confirmEvent(Long eventId, String operator)
    {
        int updated = jdbcTemplate.update(
                "update xy_benefit_event set status='CONFIRMED',confirmed_time=now(),update_by=? "
                        + "where event_id=? and status in('OPEN','CONFIRMED')", operator, eventId);
        if (updated != 1) throw new ServiceException("只有已开放场次可以确认开始");
        int sent = dispatchEventNotices(eventId, "START");
        Map<String, Object> result = adminEvent(eventId);
        result.put("noticeSentCount", sent);
        return result;
    }

    public Map<String, Object> cancelEvent(Long eventId, String reason, String operator)
    {
        String safeReason = string(reason).trim();
        if (safeReason.length() < 2 || safeReason.length() > 500) throw new ServiceException("请填写取消原因");
        int updated = jdbcTemplate.update(
                "update xy_benefit_event set status='CANCELED',canceled_time=now(),cancel_reason=?,update_by=? "
                        + "where event_id=? and status in('DRAFT','OPEN','CONFIRMED')", safeReason, operator, eventId);
        if (updated != 1) throw new ServiceException("当前场次不能取消");
        int sent = dispatchEventNotices(eventId, "CANCEL");
        List<Long> bookingIds = jdbcTemplate.queryForList(
                "select booking_id from xy_benefit_booking where event_id=? and status='BOOKED' order by booking_id",
                Long.class, eventId);
        int submitted = 0;
        List<Long> failed = new ArrayList<>();
        for (Long bookingId : bookingIds)
        {
            try { initiateBookingRefund(bookingId, safeReason, operator); submitted++; }
            catch (RuntimeException ex) { failed.add(bookingId); }
        }
        Map<String, Object> result = adminEvent(eventId);
        result.put("noticeSentCount", sent);
        result.put("submittedCount", submitted);
        result.put("failedBookingIds", failed);
        return result;
    }

    public Map<String, Object> refundBooking(Long bookingId, String reason, String operator)
    {
        initiateBookingRefund(bookingId, reason, operator);
        return jdbcTemplate.queryForMap(
                "select b.booking_id as bookingId,b.status,r.status as refundStatus,r.refund_no as refundNo "
                        + "from xy_benefit_booking b left join xy_benefit_refund r on r.booking_id=b.booking_id "
                        + "where b.booking_id=?", bookingId);
    }

    private void initiateBookingRefund(Long bookingId, String reason, String operator)
    {
        String safeReason = string(reason).trim();
        if (safeReason.length() < 2 || safeReason.length() > 500) throw new ServiceException("请填写处理原因");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select b.booking_id,b.status,p.payment_id,p.payment_no,p.amount,p.channel,p.status as payment_status,"
                        + "p.transaction_id,r.refund_no,r.status as refund_status from xy_benefit_booking b "
                        + "join xy_payment p on p.business_type='BENEFIT_EVENT' and p.business_id=b.booking_id "
                        + "left join xy_benefit_refund r on r.booking_id=b.booking_id where b.booking_id=?", bookingId);
        if (rows.isEmpty()) throw new ServiceException("报名记录或支付流水不存在");
        Map<String, Object> row = rows.get(0);
        if ("SUCCESS".equals(string(row.get("refund_status")))) return;
        if ("PROCESSING".equals(string(row.get("refund_status")))) return;
        if (!"SUCCESS".equals(string(row.get("payment_status")))) throw new ServiceException("该报名没有可处理的成功支付流水");
        int locked = jdbcTemplate.update(
                "update xy_benefit_booking set status='REFUNDING',close_reason=? where booking_id=? and status='BOOKED'",
                safeReason, bookingId);
        if (locked != 1) throw new ServiceException("该报名当前不能重复处理");

        String refundNo = StringUtils.isEmpty(string(row.get("refund_no"))) ? nextNo("FR") : string(row.get("refund_no"));
        if (StringUtils.isEmpty(string(row.get("refund_no"))))
            jdbcTemplate.update(
                    "insert into xy_benefit_refund(booking_id,refund_no,amount,reason,status,create_by) values(?,?,?,?, 'PROCESSING',?)",
                    bookingId, refundNo, row.get("amount"), safeReason, operator);
        else
            jdbcTemplate.update(
                    "update xy_benefit_refund set status='PROCESSING',reason=?,create_by=?,refund_id=null where booking_id=?",
                    safeReason, operator, bookingId);
        jdbcTemplate.update("update xy_payment set status='REFUNDING' where payment_id=?", row.get("payment_id"));
        try
        {
            if ("DEMO".equals(string(row.get("channel"))))
            {
                completeBenefitRefund(refundNo, "DEMO-" + nextNo("RID"));
                return;
            }
            if (!"WECHAT".equals(string(row.get("channel")))) throw new ServiceException("该支付渠道不能在线处理");
            int amountFen = cents(row.get("amount"));
            Map<String, Object> response = payService.refund(string(row.get("transaction_id")), refundNo,
                    amountFen, amountFen, safeReason);
            String refundId = string(response.get("refund_id"));
            jdbcTemplate.update("update xy_benefit_refund set refund_id=? where refund_no=?", refundId, refundNo);
            if ("SUCCESS".equals(string(response.get("status")))) completeBenefitRefund(refundNo, refundId);
        }
        catch (RuntimeException ex)
        {
            failBenefitRefund(refundNo, null);
            throw ex;
        }
    }

    @Transactional
    public boolean completeRefundIfApplicable(String refundNo, String refundId)
    {
        Integer exists = jdbcTemplate.queryForObject(
                "select count(1) from xy_benefit_refund where refund_no=?", Integer.class, refundNo);
        if (exists == null || exists == 0) return false;
        completeBenefitRefund(refundNo, refundId);
        return true;
    }

    private void completeBenefitRefund(String refundNo, String refundId)
    {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select booking_id,status from xy_benefit_refund where refund_no=? for update", refundNo);
        if (rows.isEmpty()) throw new ServiceException("福利钓资金处理记录不存在");
        Map<String, Object> row = rows.get(0);
        if ("SUCCESS".equals(string(row.get("status")))) return;
        Long bookingId = ((Number) row.get("booking_id")).longValue();
        jdbcTemplate.update(
                "update xy_benefit_refund set status='SUCCESS',refund_id=?,complete_time=now() where refund_no=?",
                refundId, refundNo);
        jdbcTemplate.update(
                "update xy_benefit_booking set status='CLOSED',seat_lock=null,member_lock=null,payment_payload=null where booking_id=?",
                bookingId);
        jdbcTemplate.update(
                "update xy_payment set status='REFUNDED' where business_type='BENEFIT_EVENT' and business_id=?",
                bookingId);
    }

    @Transactional
    public boolean failRefundIfApplicable(String refundNo, String refundId)
    {
        Integer exists = jdbcTemplate.queryForObject(
                "select count(1) from xy_benefit_refund where refund_no=?", Integer.class, refundNo);
        if (exists == null || exists == 0) return false;
        failBenefitRefund(refundNo, refundId);
        return true;
    }

    private void failBenefitRefund(String refundNo, String refundId)
    {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select booking_id,status from xy_benefit_refund where refund_no=? for update", refundNo);
        if (rows.isEmpty() || "SUCCESS".equals(string(rows.get(0).get("status")))) return;
        Long bookingId = ((Number) rows.get(0).get("booking_id")).longValue();
        jdbcTemplate.update(
                "update xy_benefit_refund set status='FAILED',refund_id=? where refund_no=?", refundId, refundNo);
        jdbcTemplate.update("update xy_benefit_booking set status='BOOKED' where booking_id=? and status='REFUNDING'", bookingId);
        jdbcTemplate.update(
                "update xy_payment set status='SUCCESS' where business_type='BENEFIT_EVENT' and business_id=?",
                bookingId);
    }

    private int dispatchEventNotices(Long eventId, String noticeType)
    {
        if (!wechatService.isBenefitNoticeConfigured(noticeType)) return 0;
        String acceptedColumn = "START".equals(noticeType) ? "start_notice_accepted" : "cancel_notice_accepted";
        String bookingStatuses = "START".equals(noticeType)
                ? "b.status='BOOKED'"
                : "b.status in('BOOKED','REFUNDING','CLOSED')";
        List<Map<String, Object>> bookings = jdbcTemplate.queryForList(
                "select b.booking_id as bookingId,m.openid,st.store_name as storeName,e.event_date as eventDate,"
                        + "e.start_time as startTime,e.cancel_reason as cancelReason from xy_benefit_booking b "
                        + "join xy_benefit_event e on e.event_id=b.event_id join xy_member m on m.member_id=b.member_id "
                        + "join xy_store st on st.store_id=e.store_id where b.event_id=? and " + bookingStatuses
                        + " and b." + acceptedColumn + "=1", eventId);
        int sent = 0;
        for (Map<String, Object> booking : bookings)
            if (sendNotice(booking, noticeType)) sent++;
        return sent;
    }

    private boolean sendNotice(Map<String, Object> booking, String noticeType)
    {
        Long bookingId = ((Number) booking.get("bookingId")).longValue();
        try
        {
            jdbcTemplate.update(
                    "insert into xy_benefit_notification_record(booking_id,notice_type,status) values(?,?,'PENDING')",
                    bookingId, noticeType);
        }
        catch (DuplicateKeyException ex)
        {
            int retry = jdbcTemplate.update(
                    "update xy_benefit_notification_record set status='PENDING',error_message=null "
                            + "where booking_id=? and notice_type=? and status='FAILED'", bookingId, noticeType);
            if (retry != 1) return false;
        }
        LocalDateTime eventTime = LocalDateTime.of(asDate(booking.get("eventDate")), asTime(booking.get("startTime")));
        String note = "START".equals(noticeType) ? string(booking.get("storeName")) : string(booking.get("cancelReason"));
        String error = wechatService.sendBenefitNotice(noticeType, string(booking.get("openid")),
                string(booking.get("storeName")), eventTime, note);
        if (error == null)
        {
            jdbcTemplate.update(
                    "update xy_benefit_notification_record set status='SENT',sent_time=now() where booking_id=? and notice_type=?",
                    bookingId, noticeType);
            return true;
        }
        jdbcTemplate.update(
                "update xy_benefit_notification_record set status='FAILED',error_message=? where booking_id=? and notice_type=?",
                error, bookingId, noticeType);
        return false;
    }

    private void dispatchDueNotices()
    {
        List<Long> startEvents = jdbcTemplate.queryForList(
                "select event_id from xy_benefit_event where status='CONFIRMED' and event_date>=curdate()", Long.class);
        for (Long eventId : startEvents) dispatchEventNotices(eventId, "START");
        List<Long> canceledEvents = jdbcTemplate.queryForList(
                "select event_id from xy_benefit_event where status='CANCELED' and event_date>=date_sub(curdate(),interval 1 day)", Long.class);
        for (Long eventId : canceledEvents) dispatchEventNotices(eventId, "CANCEL");
    }

    private void expirePendingBookings()
    {
        jdbcTemplate.update(
                "update xy_payment p join xy_benefit_booking b on p.business_type='BENEFIT_EVENT' and p.business_id=b.booking_id "
                        + "set p.status='CLOSED' where b.status='PENDING_PAYMENT' and b.expires_time<now() and p.status='PENDING'");
        jdbcTemplate.update(
                "update xy_benefit_booking set status='CLOSED',seat_lock=null,member_lock=null,payment_payload=null "
                        + "where status='PENDING_PAYMENT' and expires_time<now()");
    }

    private String displayEventStatus(String status, boolean beforeDeadline)
    {
        if ("CANCELED".equals(status)) return "专场已取消";
        if ("FINISHED".equals(status)) return "专场已结束";
        if ("CONFIRMED".equals(status)) return beforeDeadline ? "报名中" : "已确认开始";
        if ("OPEN".equals(status)) return beforeDeadline ? "报名中" : "报名已截止";
        return "暂未开放";
    }

    private String displayBookingStatus(String bookingStatus, String eventStatus)
    {
        if ("CANCELED".equals(eventStatus)) return "专场已取消";
        if ("FINISHED".equals(eventStatus)) return "专场已结束";
        if ("BOOKED".equals(bookingStatus)) return "已报名";
        if ("PENDING_PAYMENT".equals(bookingStatus)) return "报名确认中";
        return "报名已关闭";
    }

    private LocalDate today() { return LocalDate.now(CHINA_ZONE); }
    private LocalTime currentTime() { return LocalTime.now(CHINA_ZONE); }
    private LocalDateTime now() { return LocalDateTime.now(CHINA_ZONE); }
    private String nextNo(String prefix)
    {
        return prefix + now().format(NO_TIME) + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
    private String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private boolean booleanValue(Object value) { return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(string(value)) || "1".equals(string(value)); }
    private Long longValue(Object value, String message)
    {
        try { return Long.valueOf(string(value)); } catch (Exception ex) { throw new ServiceException(message); }
    }
    private int intValue(Object value, String message)
    {
        try { return Integer.parseInt(string(value)); } catch (Exception ex) { throw new ServiceException(message); }
    }
    private int cents(Object value)
    {
        try { return new BigDecimal(string(value)).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).intValueExact(); }
        catch (Exception ex) { throw new ServiceException("金额格式不合法"); }
    }
    private LocalDate asDate(Object value)
    {
        if (value instanceof java.sql.Date) return ((java.sql.Date) value).toLocalDate();
        return LocalDate.parse(string(value));
    }
    private LocalTime asTime(Object value)
    {
        if (value instanceof java.sql.Time) return ((java.sql.Time) value).toLocalTime();
        String text = string(value);
        return LocalTime.parse(text.length() >= 5 ? text.substring(0, 5) : text);
    }
}
