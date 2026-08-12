package com.ruoyi.web.service.xy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;

/**
 * 钓虾核心业务服务。
 *
 * 所有金额、库存和座位占用均在服务端计算/校验，客户端只提交业务意图。
 */
@Service
public class XyBusinessService
{
    private static final String MEMBER_TOKEN_PREFIX = "xy:member:token:";
    private static final String MEMBER_VERIFY_PREFIX = "xy:member:verify:";
    private static final String MEMBER_VERIFY_MEMBER_PREFIX = "xy:member:verify:member:";
    private static final int MEMBER_VERIFY_EXPIRES_SECONDS = 10;
    private static final int RESERVATION_WINDOW_DAYS = 30;
    private static final String MEMBER_PRODUCT_DISCOUNT_RATE_KEY = "member_product_discount_rate";
    private static final BigDecimal DEFAULT_MEMBER_PRODUCT_DISCOUNT_RATE = new BigDecimal("0.95");
    private static final BigDecimal MAX_PAYMENT_AMOUNT = new BigDecimal("21474836.47");
    private static final String PAYMENT_CHANNEL_WECHAT = "WECHAT";
    private static final String PAYMENT_CHANNEL_OFFLINE = "OFFLINE";
    private static final String PAYMENT_CHANNEL_DEMO = "DEMO";
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final DateTimeFormatter API_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final RedisCache redisCache;

    @Value("${xy.order-expire-minutes:30}")
    private int orderExpireMinutes;

    @Value("${xy.offline-payment-expire-minutes:1440}")
    private int offlinePaymentExpireMinutes;

    @Value("${xy.member-pending-order-limit:3}")
    private int memberPendingOrderLimit;

    public XyBusinessService(JdbcTemplate jdbcTemplate, RedisCache redisCache)
    {
        this.jdbcTemplate = jdbcTemplate;
        this.redisCache = redisCache;
    }

    /** 自动关闭超时订单、恢复库存，并把已经错过时段的预约标记为未到店。 */
    @Scheduled(fixedDelayString = "${xy.maintenance-interval-ms:60000}")
    @Transactional
    public void maintainExpiredBusinessRecords()
    {
        int expireMinutes = Math.max(5, Math.min(orderExpireMinutes, 1440));
        List<Map<String, Object>> expiredOrders = jdbcTemplate.queryForList(
                "select o.order_id from xy_order o "
                        + "where o.status='PENDING_PAYMENT' and o.create_time < date_sub(now(), interval "
                        + expireMinutes + " minute) and not exists (select 1 from xy_payment p where p.business_type='ORDER' "
                        + "and p.business_id=o.order_id and p.channel='OFFLINE' and p.status='PENDING') "
                        + "order by o.order_id limit 100 for update");
        for (Map<String, Object> row : expiredOrders)
        {
            closePendingOrder(((Number) row.get("order_id")).longValue());
        }
        int offlineExpireMinutes = effectiveOfflinePaymentExpireMinutes();
        List<Map<String, Object>> expiredOfflineOrders = jdbcTemplate.queryForList(
                "select o.order_id from xy_order o join xy_payment p on p.business_type='ORDER' and p.business_id=o.order_id "
                        + "where o.status='PENDING_PAYMENT' and p.channel='OFFLINE' and p.status='PENDING' "
                        + "and p.create_time < date_sub(now(), interval " + offlineExpireMinutes
                        + " minute) order by o.order_id limit 100 for update");
        for (Map<String, Object> row : expiredOfflineOrders)
        {
            closePendingOrder(((Number) row.get("order_id")).longValue());
        }
        jdbcTemplate.update("update xy_membership_order o join xy_payment p on p.business_type='MEMBERSHIP' and p.business_id=o.membership_order_id set o.status='CANCELED',p.status='CLOSED' where o.status='PENDING_PAYMENT' and p.status='PENDING' and p.channel<>'OFFLINE' and o.create_time < date_sub(now(), interval " + expireMinutes + " minute)");
        jdbcTemplate.update("update xy_membership_order o join xy_payment p on p.business_type='MEMBERSHIP' and p.business_id=o.membership_order_id set o.status='CANCELED',p.status='CLOSED' where o.status='PENDING_PAYMENT' and p.status='PENDING' and p.channel='OFFLINE' and p.create_time < date_sub(now(), interval " + offlineExpireMinutes + " minute)");
        jdbcTemplate.update("update xy_reservation r join xy_reservation_slot s on s.slot_id=r.slot_id set r.status='NO_SHOW',r.seat_lock=null where r.status='BOOKED' and (r.reservation_date<curdate() or (r.reservation_date=curdate() and s.end_time<curtime()))");
    }

    private void closePendingOrder(Long orderId)
    {
        int closed = jdbcTemplate.update(
                "update xy_order set status='CANCELED' where order_id=? and status='PENDING_PAYMENT'", orderId);
        if (closed != 1) return;
        jdbcTemplate.update(
                "update xy_product p join xy_order_item i on i.product_id=p.product_id set p.stock=p.stock+i.quantity where i.order_id=?",
                orderId);
        jdbcTemplate.update(
                "update xy_payment set status='CLOSED' where business_type='ORDER' and business_id=? and status='PENDING'",
                orderId);
    }

    private int effectiveOfflinePaymentExpireMinutes()
    {
        return Math.max(60, Math.min(offlinePaymentExpireMinutes, 10080));
    }

    public Long requireMember(String memberToken)
    {
        if (StringUtils.isEmpty(memberToken))
        {
            throw new ServiceException("登录已失效，请重新登录", 401);
        }
        Long memberId = redisCache.getCacheObject(MEMBER_TOKEN_PREFIX + memberToken);
        if (memberId == null)
        {
            throw new ServiceException("登录已失效，请重新登录", 401);
        }
        Integer enabled = jdbcTemplate.queryForObject("select count(1) from xy_member where member_id = ? and status = '0'",
                Integer.class, memberId);
        if (enabled == null || enabled == 0)
        {
            throw new ServiceException("会员账号不可用", 403);
        }
        return memberId;
    }

    /** 商品浏览可匿名进行；存在有效小程序会话时才计算会员价。 */
    public Long optionalMember(String memberToken)
    {
        if (StringUtils.isEmpty(memberToken)) return null;
        Long memberId = redisCache.getCacheObject(MEMBER_TOKEN_PREFIX + memberToken);
        if (memberId == null) return null;
        Integer enabled = jdbcTemplate.queryForObject("select count(1) from xy_member where member_id=? and status='0'",
                Integer.class, memberId);
        return enabled != null && enabled > 0 ? memberId : null;
    }

    /** 供微信 code2session 成功后建立安全会话。 */
    @Transactional
    public Map<String, Object> loginByOpenId(String openid, String unionid)
    {
        return loginByOpenId(openid, unionid, null);
    }

    /** 首次登录或尚未归属邀请人时，可用有效邀请码绑定一次，后续登录不能改绑。 */
    @Transactional
    public Map<String, Object> loginByOpenId(String openid, String unionid, String inviteCode)
    {
        String normalizedInviteCode = StringUtils.isEmpty(inviteCode) ? null : inviteCode.trim();
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "select member_id,nickname,avatar_url,status,inviter_member_id from xy_member where openid=? for update", openid);
        Long memberId;
        if (existing.isEmpty())
        {
            Long inviterMemberId = resolveInviterMemberId(normalizedInviteCode);
            String generatedInviteCode = generateInviteCode();
            jdbcTemplate.update("insert into xy_member(openid,unionid,invite_code,inviter_member_id) values(?,?,?,?)",
                    openid, unionid, generatedInviteCode, inviterMemberId);
            memberId = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        }
        else
        {
            memberId = ((Number) existing.get(0).get("member_id")).longValue();
            if (!"0".equals(String.valueOf(existing.get(0).get("status"))))
                throw new ServiceException("会员账号不可用", 403);
            // 已经成功绑定过邀请人的会员只更新微信身份，不再解析后来携带的分享参数，
            // 防止失效/伪造的邀请码反过来阻断正常登录，同时保持邀请关系不可改绑。
            if (existing.get(0).get("inviter_member_id") == null)
            {
                Long inviterMemberId = resolveInviterMemberId(normalizedInviteCode);
                if (inviterMemberId != null && inviterMemberId.equals(memberId))
                    throw new ServiceException("不能绑定自己的邀请码");
                jdbcTemplate.update(
                        "update xy_member set unionid=coalesce(?,unionid),inviter_member_id=? where member_id=? and inviter_member_id is null",
                        unionid, inviterMemberId, memberId);
            }
            else
            {
                jdbcTemplate.update("update xy_member set unionid=coalesce(?,unionid) where member_id=?", unionid, memberId);
            }
        }
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        redisCache.setCacheObject(MEMBER_TOKEN_PREFIX + token, memberId, 30, TimeUnit.DAYS);
        Map<String, Object> result = new HashMap<>();
        result.put("memberToken", token);
        result.put("member", memberProfile(memberId));
        Long boundInviter = jdbcTemplate.queryForObject(
                "select inviter_member_id from xy_member where member_id=?", Long.class, memberId);
        result.put("invitationBound", boundInviter != null);
        return result;
    }

    private Long resolveInviterMemberId(String inviteCode)
    {
        if (inviteCode == null) return null;
        if (!inviteCode.matches("^\\d{6}$")) throw new ServiceException("邀请码格式不正确");
        List<Long> inviters = jdbcTemplate.query(
                "select member_id from xy_member where invite_code=? and status='0'",
                (rs, rowNum) -> rs.getLong(1), inviteCode);
        if (inviters.isEmpty()) throw new ServiceException("邀请码无效");
        return inviters.get(0);
    }

    public Map<String, Object> memberProfile(Long memberId)
    {
        List<Map<String, Object>> list = jdbcTemplate.queryForList("select member_id as memberId, nickname, avatar_url as avatarUrl, mobile, invite_code as inviteCode, create_time as createTime from xy_member where member_id = ?", memberId);
        if (list.isEmpty())
        {
            throw new ServiceException("会员不存在");
        }
        Map<String, Object> profile = list.get(0);
        profile.put("card", currentCard(memberId));
        return profile;
    }

    @Transactional
    public void updateMemberProfile(Long memberId, Map<String, Object> input)
    {
        String nickname = required(input, "nickname", "昵称不能为空");
        checkLength(nickname, 100, "昵称不能超过100个字符");
        String mobile = input.get("mobile") == null ? null : String.valueOf(input.get("mobile")).trim();
        if (mobile != null && !mobile.isEmpty() && !mobile.matches("^1\\d{10}$")) throw new ServiceException("手机号格式不正确");
        jdbcTemplate.update("update xy_member set nickname=?, mobile=?, avatar_url=? where member_id=?", nickname, mobile, input.get("avatarUrl"), memberId);
    }

    public Map<String, Object> currentCard(Long memberId)
    {
        List<Map<String, Object>> cards = jdbcTemplate.queryForList("select c.card_id as cardId, c.card_no as cardNo, c.start_date as startDate, c.expire_date as expireDate, c.status, c.usage_count as usageCount, p.plan_name as planName, p.daily_reservation_limit as dailyReservationLimit from xy_membership_card c join xy_membership_plan p on p.plan_id = c.plan_id where c.member_id = ? and c.status = 'ACTIVE' and c.start_date <= curdate() and c.expire_date >= curdate() order by c.expire_date desc limit 1", memberId);
        return cards.isEmpty() ? null : cards.get(0);
    }

    public List<Map<String, Object>> listProducts(Long productId, Long memberId)
    {
        String sql = "select product_id as productId, product_name as productName, category_name as categoryName, cover_url as coverUrl, detail_text as detailText, sale_price as salePrice, member_discount_enabled as memberDiscountEnabled, stock from xy_product where status = '0'";
        List<Object> args = new ArrayList<>();
        if (productId != null)
        {
            sql += " and product_id = ?";
            args.add(productId);
        }
        sql += " order by sort_order asc, product_id desc";
        List<Map<String, Object>> products = jdbcTemplate.queryForList(sql, args.toArray());
        boolean activeMember = memberId != null && currentCard(memberId) != null;
        BigDecimal rate = activeMember ? memberProductDiscountRate() : BigDecimal.ONE;
        for (Map<String, Object> product : products)
        {
            BigDecimal originalPrice = new BigDecimal(product.get("salePrice").toString());
            boolean eligible = isMemberDiscountEnabled(product.get("memberDiscountEnabled"));
            BigDecimal memberPrice = activeMember && eligible ? originalPrice.multiply(rate).setScale(2, RoundingMode.HALF_UP) : originalPrice;
            product.put("memberDiscountEligible", eligible);
            product.put("memberDiscountRate", activeMember && eligible ? rate : BigDecimal.ONE);
            product.put("memberPrice", memberPrice);
            product.put("memberDiscountAmount", originalPrice.subtract(memberPrice));
        }
        return products;
    }

    public Map<String, Object> memberDiscountSettings()
    {
        Map<String, Object> result = new HashMap<>();
        BigDecimal rate = memberProductDiscountRate();
        result.put("discountRate", rate);
        result.put("discountLabel", rate.multiply(BigDecimal.TEN).stripTrailingZeros().toPlainString() + "折");
        return result;
    }

    @Transactional
    public void saveMemberDiscountSettings(Map<String, Object> input)
    {
        BigDecimal rate = decimal(input.get("discountRate"), "会员折扣率不合法");
        if (rate.compareTo(new BigDecimal("0.01")) < 0 || rate.compareTo(BigDecimal.ONE) > 0)
        {
            throw new ServiceException("会员折扣率必须在 0.01 到 1 之间");
        }
        if (decimalScale(rate) > 4) throw new ServiceException("会员折扣率最多保留4位小数");
        jdbcTemplate.update("insert into xy_business_setting(setting_key,setting_value) values(?,?) on duplicate key update setting_value=values(setting_value)",
                MEMBER_PRODUCT_DISCOUNT_RATE_KEY, rate.stripTrailingZeros().toPlainString());
    }

    private BigDecimal memberProductDiscountRate()
    {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("select setting_value from xy_business_setting where setting_key=?",
                MEMBER_PRODUCT_DISCOUNT_RATE_KEY);
        if (rows.isEmpty()) return DEFAULT_MEMBER_PRODUCT_DISCOUNT_RATE;
        try
        {
            BigDecimal rate = new BigDecimal(String.valueOf(rows.get(0).get("setting_value")));
            return rate.compareTo(BigDecimal.ZERO) > 0 && rate.compareTo(BigDecimal.ONE) <= 0 ? rate : DEFAULT_MEMBER_PRODUCT_DISCOUNT_RATE;
        }
        catch (Exception ex)
        {
            return DEFAULT_MEMBER_PRODUCT_DISCOUNT_RATE;
        }
    }

    private boolean isMemberDiscountEnabled(Object value)
    {
        return value instanceof Number ? ((Number) value).intValue() == 1 : "1".equals(String.valueOf(value));
    }

    public List<Map<String, Object>> listStores()
    {
        return jdbcTemplate.queryForList("select store_id as storeId, store_name as storeName, address, phone, longitude, latitude, business_hours as businessHours from xy_store where status='0' order by store_id");
    }

    public List<Map<String, Object>> listMembershipPlans()
    {
        return jdbcTemplate.queryForList("select plan_id as planId, plan_name as planName, amount, duration_days as durationDays, daily_reservation_limit as dailyReservationLimit from xy_membership_plan where status='0' and duration_days=30 order by sort_order, plan_id limit 1");
    }

    public Map<String, Object> reservationAvailability(Long storeId, LocalDate reservationDate)
    {
        validateReservationDate(reservationDate);
        Integer activeStore = jdbcTemplate.queryForObject(
                "select count(1) from xy_store where store_id=? and status='0'", Integer.class, storeId);
        if (activeStore == null || activeStore == 0) throw new ServiceException("门店不存在或暂未营业");
        Map<String, Object> result = new HashMap<>();
        result.put("date", reservationDate.toString());
        List<Map<String, Object>> slots = jdbcTemplate.queryForList("select s.slot_id as slotId, date_format(s.start_time, '%H:%i') as startTime, date_format(s.end_time, '%H:%i') as endTime, (select count(1) from xy_reservation r where r.slot_id = s.slot_id and r.reservation_date = ? and r.seat_lock = 1) as bookedCount, (select count(1) from xy_seat se where se.store_id = s.store_id and se.status = '0') as totalCount from xy_reservation_slot s where s.store_id = ? and s.status = '0' order by s.sort_order, s.start_time", reservationDate, storeId);
        for (Map<String, Object> slot : slots)
        {
            boolean bookable = !reservationDate.equals(LocalDate.now()) || LocalTime.now().isBefore(LocalTime.parse(String.valueOf(slot.get("startTime"))));
            slot.put("bookable", bookable);
        }
        result.put("slots", slots);
        result.put("seats", jdbcTemplate.queryForList("select se.seat_id as seatId, se.seat_code as seatCode, se.zone_name as zoneName, coalesce(group_concat(r.slot_id), '') as bookedSlotIds from xy_seat se left join xy_reservation r on r.seat_id=se.seat_id and r.reservation_date=? and r.seat_lock=1 where se.store_id=? and se.status='0' group by se.seat_id, se.seat_code, se.zone_name, se.sort_order order by se.sort_order, se.seat_code", reservationDate, storeId));
        result.put("sameDayRolloverRule", "每位会员同时只能保留1个待到场预约；到店签到后，自当前场次结束前10分钟起，可不限次续约当天更晚的空余时段");
        return result;
    }

    @Transactional
    public Map<String, Object> createReservation(Long memberId, Long storeId, Long slotId, Long seatId, LocalDate reservationDate)
    {
        validateReservationDate(reservationDate);
        Map<String, Object> card = currentCard(memberId);
        if (card == null)
        {
            throw new ServiceException("请先开通有效会员卡");
        }
        // 串行化同一会员的预约请求，防止连续点击或并发请求绕过单预约限制。
        jdbcTemplate.queryForObject("select member_id from xy_member where member_id=? for update", Long.class, memberId);
        List<Map<String, Object>> slotRows = jdbcTemplate.queryForList(
                "select s.start_time,s.end_time from xy_reservation_slot s join xy_store st on st.store_id=s.store_id and st.status='0' where s.slot_id=? and s.store_id=? and s.status='0'",
                slotId, storeId);
        if (slotRows.isEmpty()) throw new ServiceException("选择的时段不可用");
        LocalTime targetStart = ((java.sql.Time) slotRows.get(0).get("start_time")).toLocalTime();
        if (reservationDate.equals(LocalDate.now()) && !LocalTime.now().isBefore(targetStart))
        {
            throw new ServiceException("当天只能预约尚未开始的时段");
        }
        Integer duplicate = jdbcTemplate.queryForObject(
                "select count(1) from xy_reservation where member_id=? and reservation_date=? and slot_id=? and status in ('BOOKED','CHECKED_IN')",
                Integer.class, memberId, reservationDate, slotId);
        if (duplicate != null && duplicate > 0)
        {
            throw new ServiceException("你已预约该时段，不能重复预约");
        }
        Integer pendingBooked = jdbcTemplate.queryForObject(
                "select count(1) from xy_reservation where member_id=? and status='BOOKED' and reservation_date>=curdate()",
                Integer.class, memberId);
        if (pendingBooked != null && pendingBooked > 0)
        {
            throw new ServiceException("你已有一条待到场预约，每次只能预约一个场次；到店签到后可按规则续约当天后续时段");
        }
        Integer checkedInToday = jdbcTemplate.queryForObject(
                "select count(1) from xy_reservation where member_id=? and status='CHECKED_IN' and reservation_date=curdate()",
                Integer.class, memberId);
        boolean rolloverAllowed = checkedInToday != null && checkedInToday > 0
                && hasSameDayRolloverPrivilege(memberId, reservationDate, targetStart);
        if (checkedInToday != null && checkedInToday > 0 && !rolloverAllowed)
        {
            throw new ServiceException("你已签到当前场次，请在场次结束前10分钟起预约当天更晚的空余时段");
        }
        Integer dailyLimit = ((Number) card.get("dailyReservationLimit")).intValue();
        Integer used = jdbcTemplate.queryForObject("select count(1) from xy_reservation where member_id = ? and reservation_date = ? and seat_lock = 1", Integer.class, memberId, reservationDate);
        if (used != null && used >= dailyLimit && !rolloverAllowed)
        {
            throw new ServiceException("该会员当天预约次数已达上限；完成签到后仅可在本人场次结束前10分钟追加预约当日后续时段");
        }
        Integer validSeat = jdbcTemplate.queryForObject("select count(1) from xy_seat where seat_id = ? and store_id = ? and status = '0'", Integer.class, seatId, storeId);
        if (validSeat == null || validSeat == 0)
        {
            throw new ServiceException("选择的时段或座位不可用");
        }
        String reservationNo = nextNo("RS");
        String verifyCode = randomDigits(8);
        try
        {
            jdbcTemplate.update("insert into xy_reservation(reservation_no, member_id, store_id, slot_id, seat_id, reservation_date, verify_code) values (?, ?, ?, ?, ?, ?, ?)", reservationNo, memberId, storeId, slotId, seatId, reservationDate, verifyCode);
        }
        catch (DuplicateKeyException ex)
        {
            throw new ServiceException("该座位刚刚被其他会员预约，请刷新后重试");
        }
        jdbcTemplate.update("update xy_membership_card set usage_count = usage_count + 1 where card_id = ?", ((Number) card.get("cardId")).longValue());
        return reservationDetail(memberId, reservationNo);
    }

    /** 签到会员从当前场次结束前 10 分钟起，可预约当日更晚的下一个空位时段。 */
    private boolean hasSameDayRolloverPrivilege(Long memberId, LocalDate reservationDate, LocalTime targetStart)
    {
        if (!LocalDate.now().equals(reservationDate)) return false;
        List<java.sql.Time> endTimes = jdbcTemplate.query(
                "select s.end_time from xy_reservation r join xy_reservation_slot s on s.slot_id=r.slot_id "
                        + "where r.member_id=? and r.reservation_date=curdate() and r.status='CHECKED_IN' "
                        + "order by s.end_time desc limit 1",
                (rs, rowNum) -> rs.getTime(1), memberId);
        if (endTimes.isEmpty()) return false;
        LocalTime currentEnd = endTimes.get(0).toLocalTime();
        return !LocalTime.now().isBefore(currentEnd.minusMinutes(10)) && !targetStart.isBefore(currentEnd);
    }
    public List<Map<String, Object>> memberReservations(Long memberId)
    {
        return jdbcTemplate.queryForList("select r.reservation_id as reservationId, r.reservation_no as reservationNo, r.reservation_date as reservationDate, r.status, r.verify_code as verifyCode, date_format(s.start_time, '%H:%i') as startTime, date_format(s.end_time, '%H:%i') as endTime, se.seat_code as seatCode, se.zone_name as zoneName, st.store_name as storeName from xy_reservation r join xy_reservation_slot s on s.slot_id = r.slot_id join xy_seat se on se.seat_id = r.seat_id join xy_store st on st.store_id = r.store_id where r.member_id = ? order by r.reservation_date desc, s.start_time desc", memberId);
    }

    public Map<String, Object> reservationDetail(Long memberId, String reservationNo)
    {
        List<Map<String, Object>> list = jdbcTemplate.queryForList("select r.reservation_id as reservationId, r.reservation_no as reservationNo, r.reservation_date as reservationDate, r.status, r.verify_code as verifyCode, r.create_time as createTime, date_format(s.start_time, '%H:%i') as startTime, date_format(s.end_time, '%H:%i') as endTime, se.seat_code as seatCode, se.zone_name as zoneName, st.store_name as storeName, st.address, st.phone from xy_reservation r join xy_reservation_slot s on s.slot_id = r.slot_id join xy_seat se on se.seat_id = r.seat_id join xy_store st on st.store_id = r.store_id where r.member_id = ? and r.reservation_no = ?", memberId, reservationNo);
        if (list.isEmpty()) throw new ServiceException("预约记录不存在");
        return list.get(0);
    }

    @Transactional
    public void cancelReservation(Long memberId, String reservationNo)
    {
        int affected = jdbcTemplate.update(
                "update xy_reservation r join xy_reservation_slot s on s.slot_id=r.slot_id "
                        + "set r.status='CANCELED',r.seat_lock=null,r.cancel_time=now() "
                        + "where r.member_id=? and r.reservation_no=? and r.status='BOOKED' "
                        + "and (r.reservation_date>curdate() or (r.reservation_date=curdate() and s.start_time>curtime()))",
                memberId, reservationNo);
        if (affected != 1) throw new ServiceException("该预约当前不能取消");
    }

    public List<Map<String, Object>> addresses(Long memberId)
    {
        return jdbcTemplate.queryForList("select address_id as addressId, receiver_name as receiverName, receiver_mobile as receiverMobile, province, city, district, detail, is_default as isDefault from xy_address where member_id = ? order by is_default desc, update_time desc", memberId);
    }

    @Transactional
    public Long saveAddress(Long memberId, Map<String, Object> input)
    {
        String receiverName = required(input, "receiverName", "收货人不能为空");
        String receiverMobile = required(input, "receiverMobile", "联系电话不能为空");
        String province = required(input, "province", "省份不能为空");
        String city = required(input, "city", "城市不能为空");
        String district = required(input, "district", "区县不能为空");
        String detail = required(input, "detail", "详细地址不能为空");
        checkLength(receiverName, 64, "收货人不能超过64个字符");
        checkLength(receiverMobile, 32, "联系电话不能超过32个字符");
        checkLength(province, 64, "省份不能超过64个字符");
        checkLength(city, 64, "城市不能超过64个字符");
        checkLength(district, 64, "区县不能超过64个字符");
        checkLength(detail, 255, "详细地址不能超过255个字符");
        Long addressId = number(input.get("addressId"));
        boolean makeDefault = Boolean.TRUE.equals(input.get("isDefault")) || addressId == null && addresses(memberId).isEmpty();
        if (makeDefault) jdbcTemplate.update("update xy_address set is_default = 0 where member_id = ?", memberId);
        if (addressId == null)
        {
            jdbcTemplate.update("insert into xy_address(member_id, receiver_name, receiver_mobile, province, city, district, detail, is_default) values (?, ?, ?, ?, ?, ?, ?, ?)", memberId, receiverName, receiverMobile, province, city, district, detail, makeDefault ? 1 : 0);
            return jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        }
        int count = jdbcTemplate.update("update xy_address set receiver_name=?, receiver_mobile=?, province=?, city=?, district=?, detail=?, is_default=? where address_id=? and member_id=?", receiverName, receiverMobile, province, city, district, detail, makeDefault ? 1 : 0, addressId, memberId);
        if (count != 1) throw new ServiceException("地址不存在");
        return addressId;
    }

    @Transactional
    public void deleteAddress(Long memberId, Long addressId)
    {
        int count = jdbcTemplate.update("delete from xy_address where address_id=? and member_id=?", addressId, memberId);
        if (count != 1) throw new ServiceException("地址不存在");
    }

    @Transactional
    public Map<String, Object> createOrder(Long memberId, Map<String, Object> input)
    {
        Long productId = number(input.get("productId"));
        Long addressId = number(input.get("addressId"));
        int quantity = integer(input.get("quantity"), 1, 1, 99, "商品数量不合法");
        String deliveryType = required(input, "deliveryType", "请指定配送方式");
        if (productId == null) throw new ServiceException("商品不能为空");
        if (!"DELIVERY".equals(deliveryType) && !"PICKUP".equals(deliveryType)) throw new ServiceException("配送方式不合法");
        // 串行化同一会员的下单，防止并发请求绕过待付款上限长期占用库存。
        jdbcTemplate.queryForObject("select member_id from xy_member where member_id=? for update", Long.class, memberId);
        int pendingLimit = Math.max(1, Math.min(memberPendingOrderLimit, 20));
        Integer pendingOrders = jdbcTemplate.queryForObject(
                "select count(1) from xy_order where member_id=? and status='PENDING_PAYMENT'", Integer.class, memberId);
        if (pendingOrders != null && pendingOrders >= pendingLimit)
            throw new ServiceException("你有较多待付款订单，请先完成付款或取消后再下单（最多" + pendingLimit + "笔）");
        List<Map<String, Object>> products = jdbcTemplate.queryForList("select product_id, product_name, cover_url, sale_price, member_discount_enabled, stock from xy_product where product_id = ? and status = '0' for update", productId);
        if (products.isEmpty()) throw new ServiceException("商品已下架或不存在");
        Map<String, Object> product = products.get(0);
        int stock = ((Number) product.get("stock")).intValue();
        if (stock < quantity) throw new ServiceException("商品库存不足");
        String snapshot = null;
        if ("DELIVERY".equals(deliveryType))
        {
            if (addressId == null) throw new ServiceException("配送订单必须选择收货地址");
            List<Map<String, Object>> addresses = jdbcTemplate.queryForList("select receiver_name, receiver_mobile, province, city, district, detail from xy_address where address_id = ? and member_id = ?", addressId, memberId);
            if (addresses.isEmpty()) throw new ServiceException("收货地址不存在");
            Map<String, Object> address = addresses.get(0);
            snapshot = address.get("receiver_name") + " " + address.get("receiver_mobile") + " " + address.get("province") + address.get("city") + address.get("district") + address.get("detail");
        }
        BigDecimal salePrice = new BigDecimal(product.get("sale_price").toString());
        boolean memberDiscount = currentCard(memberId) != null && isMemberDiscountEnabled(product.get("member_discount_enabled"));
        BigDecimal memberRate = memberDiscount ? memberProductDiscountRate() : BigDecimal.ONE;
        BigDecimal unitPrice = memberDiscount ? salePrice.multiply(memberRate).setScale(2, RoundingMode.HALF_UP) : salePrice;
        BigDecimal total = salePrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal discountedTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal discount = total.subtract(discountedTotal);
        BigDecimal freight = BigDecimal.ZERO;
        BigDecimal payable = discountedTotal.add(freight);
        if (payable.compareTo(MAX_PAYMENT_AMOUNT) > 0) throw new ServiceException("订单金额超过单笔交易上限");
        String orderNo = nextNo("OD");
        int reduced = jdbcTemplate.update("update xy_product set stock = stock - ? where product_id = ? and stock >= ?", quantity, productId, quantity);
        if (reduced != 1) throw new ServiceException("商品库存不足，请刷新后重试");
        jdbcTemplate.update("insert into xy_order(order_no, member_id, address_id, delivery_type, total_amount, discount_amount, member_discount_rate, freight_amount, payable_amount, receiver_snapshot) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", orderNo, memberId, addressId, deliveryType, total, discount, memberRate, freight, payable, snapshot);
        Long orderId = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        jdbcTemplate.update("insert into xy_order_item(order_id, product_id, product_name, cover_url, sale_price, quantity, subtotal_amount) values (?, ?, ?, ?, ?, ?, ?)", orderId, productId, product.get("product_name"), product.get("cover_url"), unitPrice, quantity, discountedTotal);
        return orderDetail(memberId, orderNo);
    }

    public List<Map<String, Object>> memberOrders(Long memberId)
    {
        return jdbcTemplate.queryForList("select o.order_id as orderId, o.order_no as orderNo, o.delivery_type as deliveryType, o.payable_amount as payableAmount, o.status, o.create_time as createTime, (select product_name from xy_order_item i where i.order_id=o.order_id order by item_id limit 1) as productName, (select cover_url from xy_order_item i where i.order_id=o.order_id order by item_id limit 1) as coverUrl, (select sum(quantity) from xy_order_item i where i.order_id=o.order_id) as quantity, (select case when a.status='RESTOCKED' then 'APPROVED' else a.status end from xy_after_sale a where a.order_id=o.order_id order by a.after_sale_id desc limit 1) as afterSaleStatus, (select case when a.status='RESTOCKED' then 1 else 0 end from xy_after_sale a where a.order_id=o.order_id order by a.after_sale_id desc limit 1) as afterSaleRestocked, (select p.channel from xy_payment p where p.business_type='ORDER' and p.business_id=o.order_id limit 1) as paymentChannel, (select p.status from xy_payment p where p.business_type='ORDER' and p.business_id=o.order_id limit 1) as paymentStatus from xy_order o where o.member_id = ? order by o.create_time desc", memberId);
    }

    public Map<String, Object> orderDetail(Long memberId, String orderNo)
    {
        List<Map<String, Object>> orders = jdbcTemplate.queryForList("select o.order_id as orderId, o.order_no as orderNo, o.delivery_type as deliveryType, o.total_amount as totalAmount, o.discount_amount as discountAmount, o.member_discount_rate as memberDiscountRate, o.freight_amount as freightAmount, o.payable_amount as payableAmount, o.paid_amount as paidAmount, o.status, o.receiver_snapshot as receiverSnapshot, o.create_time as createTime, (select case when a.status='RESTOCKED' then 'APPROVED' else a.status end from xy_after_sale a where a.order_id=o.order_id order by a.after_sale_id desc limit 1) as afterSaleStatus, (select case when a.status='RESTOCKED' then 1 else 0 end from xy_after_sale a where a.order_id=o.order_id order by a.after_sale_id desc limit 1) as afterSaleRestocked, (select p.payment_no from xy_payment p where p.business_type='ORDER' and p.business_id=o.order_id limit 1) as paymentNo, (select p.channel from xy_payment p where p.business_type='ORDER' and p.business_id=o.order_id limit 1) as paymentChannel, (select p.status from xy_payment p where p.business_type='ORDER' and p.business_id=o.order_id limit 1) as paymentStatus from xy_order o where o.member_id = ? and o.order_no = ?", memberId, orderNo);
        if (orders.isEmpty()) throw new ServiceException("订单不存在");
        Map<String, Object> order = orders.get(0);
        order.put("items", jdbcTemplate.queryForList("select product_id as productId, product_name as productName, cover_url as coverUrl, sale_price as salePrice, quantity, subtotal_amount as subtotalAmount from xy_order_item where order_id = ?", order.get("orderId")));
        if (PAYMENT_CHANNEL_OFFLINE.equals(String.valueOf(order.get("paymentChannel")))
                && "PENDING".equals(String.valueOf(order.get("paymentStatus")))
                && order.get("paymentNo") != null)
        {
            order.put("paymentExpireTime", offlineExpiry(String.valueOf(order.get("paymentNo"))).get("expireTime"));
        }
        return order;
    }

    @Transactional
    public Map<String,Object> createOrderPayment(Long memberId, String orderNo, XyWechatPayService payService)
    {
        List<Map<String,Object>> list = jdbcTemplate.queryForList(
                "select o.order_id,o.payable_amount,m.openid from xy_order o join xy_member m on m.member_id=o.member_id where o.member_id=? and o.order_no=? and o.status='PENDING_PAYMENT' for update",
                memberId, orderNo);
        if (list.isEmpty()) throw new ServiceException("当前订单不能发起付款");
        Map<String,Object> row = list.get(0);
        Long orderId = ((Number) row.get("order_id")).longValue();
        String requestedChannel = paymentChannel(payService);

        String paymentNo;
        String channel;
        List<Map<String,Object>> existing = jdbcTemplate.queryForList(
                "select payment_no,channel from xy_payment where business_type='ORDER' and business_id=? and status='PENDING' for update",
                orderId);
        if (existing.isEmpty())
        {
            paymentNo = nextNo("PY");
            channel = requestedChannel;
            jdbcTemplate.update("insert into xy_payment(payment_no,member_id,business_type,business_id,amount,channel) values(?,?,?,?,?,?)",
                    paymentNo, memberId, "ORDER", orderId, row.get("payable_amount"), channel);
        }
        else
        {
            paymentNo = String.valueOf(existing.get(0).get("payment_no"));
            channel = String.valueOf(existing.get(0).get("channel"));
            if (PAYMENT_CHANNEL_WECHAT.equals(channel) && PAYMENT_CHANNEL_OFFLINE.equals(requestedChannel))
                throw new ServiceException("该订单已发起微信支付，请先完成支付或取消订单后重新下单");
        }

        int totalFen = cents(row.get("payable_amount"));
        if (PAYMENT_CHANNEL_DEMO.equals(channel))
        {
            completeOrderPayment(paymentNo, "DEMO-" + nextNo("TX"), totalFen);
            Map<String,Object> result = new HashMap<>();
            result.put("demoPayment", true);
            result.put("paid", true);
            result.put("orderNo", orderNo);
            return result;
        }
        if (PAYMENT_CHANNEL_OFFLINE.equals(channel))
            return offlinePendingResult(paymentNo, orderNo, "ORDER");
        if (!PAYMENT_CHANNEL_WECHAT.equals(channel)) throw new ServiceException("支付渠道不可用");
        return payService.jsapi(paymentNo, String.valueOf(row.get("openid")), totalFen);
    }

    @Transactional
    public void completeOrderPayment(String paymentNo, String transactionId, Integer totalFen)
    {
        completePayment(paymentNo, transactionId, totalFen, null);
    }

    /** 微信回调只能完成 WECHAT 渠道流水，防止跨渠道误核销。 */
    @Transactional
    public void completeWechatPayment(String paymentNo, String transactionId, Integer totalFen)
    {
        completePayment(paymentNo, transactionId, totalFen, PAYMENT_CHANNEL_WECHAT);
    }

    private void completePayment(String paymentNo, String transactionId, Integer totalFen, String expectedChannel)
    {
        List<Map<String,Object>> rows = jdbcTemplate.queryForList(
                "select payment_id,business_type,business_id,member_id,amount,channel,status,transaction_id from xy_payment where payment_no=? for update",
                paymentNo);
        if (rows.isEmpty()) throw new ServiceException("支付单不存在");
        Map<String,Object> payment = rows.get(0);
        if (expectedChannel != null && !expectedChannel.equals(String.valueOf(payment.get("channel"))))
            throw new ServiceException("支付回调渠道不匹配");
        if (totalFen == null || cents(payment.get("amount")) != totalFen)
            throw new ServiceException("支付金额校验失败");
        if (StringUtils.isEmpty(transactionId)) throw new ServiceException("支付交易号不能为空");
        if ("SUCCESS".equals(payment.get("status")))
        {
            if (payment.get("transaction_id") != null && !transactionId.equals(String.valueOf(payment.get("transaction_id"))))
                throw new ServiceException("支付交易号与已入账记录不一致");
            return;
        }
        if (!"PENDING".equals(payment.get("status"))) throw new ServiceException("支付单状态异常");

        Long businessId = ((Number) payment.get("business_id")).longValue();
        String businessType = String.valueOf(payment.get("business_type"));
        if ("ORDER".equals(businessType))
        {
            int updated = jdbcTemplate.update("update xy_order set status='PAID',paid_amount=payable_amount,paid_time=now() where order_id=? and status='PENDING_PAYMENT'", businessId);
            if (updated != 1) throw new ServiceException("订单状态异常，无法完成支付");
        }
        else if ("MEMBERSHIP".equals(businessType))
        {
            activateMembership(businessId, paymentNo);
        }
        else
        {
            throw new ServiceException("支付业务类型不合法");
        }
        jdbcTemplate.update("update xy_payment set status='SUCCESS',transaction_id=?,paid_time=now() where payment_no=?", transactionId, paymentNo);
    }

    @Transactional
    public Map<String,Object> createMembershipPayment(Long memberId, Long planId, XyWechatPayService payService)
    {
        jdbcTemplate.queryForObject("select member_id from xy_member where member_id=? for update", Long.class, memberId);
        List<Map<String,Object>> plans = jdbcTemplate.queryForList(
                "select plan_id,plan_name,amount from xy_membership_plan where plan_id=? and status='0' and duration_days=30",
                planId);
        if (plans.isEmpty()) throw new ServiceException("包月会员方案不存在或已下架");
        Map<String,Object> plan = plans.get(0);
        String requestedChannel = paymentChannel(payService);

        // 同一会员和套餐同时只保留一张待收款单；不论微信还是线下重复点击都复用它。
        List<Map<String,Object>> pending = jdbcTemplate.queryForList(
                "select o.order_no,p.payment_no,p.channel,p.amount,m.openid from xy_membership_order o join xy_payment p on p.business_type='MEMBERSHIP' and p.business_id=o.membership_order_id join xy_member m on m.member_id=o.member_id where o.member_id=? and o.plan_id=? and o.status='PENDING_PAYMENT' and p.status='PENDING' order by o.membership_order_id desc limit 1 for update",
                memberId, planId);
        if (!pending.isEmpty())
        {
            Map<String,Object> existing = pending.get(0);
            String paymentNo = String.valueOf(existing.get("payment_no"));
            String orderNo = String.valueOf(existing.get("order_no"));
            String channel = String.valueOf(existing.get("channel"));
            if (PAYMENT_CHANNEL_WECHAT.equals(channel) && PAYMENT_CHANNEL_OFFLINE.equals(requestedChannel))
                throw new ServiceException("该开卡申请已发起微信支付，请稍后重试或联系工作人员关闭");
            if (PAYMENT_CHANNEL_OFFLINE.equals(channel)) return offlinePendingResult(paymentNo, orderNo, "MEMBERSHIP");
            if (PAYMENT_CHANNEL_DEMO.equals(channel))
            {
                completeOrderPayment(paymentNo, "DEMO-" + nextNo("TX"), cents(existing.get("amount")));
                Map<String,Object> result = new HashMap<>();
                result.put("demoPayment", true);
                result.put("paid", true);
                result.put("orderNo", orderNo);
                return result;
            }
            if (PAYMENT_CHANNEL_WECHAT.equals(channel))
                return payService.jsapi(paymentNo, String.valueOf(existing.get("openid")), cents(existing.get("amount")), "钓虾包月会员");
            throw new ServiceException("支付渠道不可用");
        }

        String orderNo = nextNo("MO");
        jdbcTemplate.update("insert into xy_membership_order(order_no,member_id,plan_id,amount) values(?,?,?,?)",
                orderNo, memberId, planId, plan.get("amount"));
        Long id = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        String paymentNo = nextNo("PY");
        jdbcTemplate.update("insert into xy_payment(payment_no,member_id,business_type,business_id,amount,channel) values(?,?,?,?,?,?)",
                paymentNo, memberId, "MEMBERSHIP", id, plan.get("amount"), requestedChannel);
        int totalFen = cents(plan.get("amount"));
        if (PAYMENT_CHANNEL_DEMO.equals(requestedChannel))
        {
            completeOrderPayment(paymentNo, "DEMO-" + nextNo("TX"), totalFen);
            Map<String,Object> result = new HashMap<>();
            result.put("demoPayment", true);
            result.put("paid", true);
            result.put("orderNo", orderNo);
            return result;
        }
        if (PAYMENT_CHANNEL_OFFLINE.equals(requestedChannel))
            return offlinePendingResult(paymentNo, orderNo, "MEMBERSHIP");
        String openid = jdbcTemplate.queryForObject("select openid from xy_member where member_id=?", String.class, memberId);
        return payService.jsapi(paymentNo, openid, totalFen, "钓虾包月会员");
    }

    private String paymentChannel(XyWechatPayService payService)
    {
        if (payService.isDemoEnabled()) return PAYMENT_CHANNEL_DEMO;
        return payService.isWechatPayConfigured() ? PAYMENT_CHANNEL_WECHAT : PAYMENT_CHANNEL_OFFLINE;
    }

    private Map<String,Object> offlinePendingResult(String paymentNo, String orderNo, String businessType)
    {
        Map<String,Object> result = new HashMap<>();
        result.put("paymentNo", paymentNo);
        result.put("orderNo", orderNo);
        result.put("businessType", businessType);
        result.put("channel", PAYMENT_CHANNEL_OFFLINE);
        result.put("offlinePayment", true);
        result.put("pendingConfirmation", true);
        result.put("paid", false);
        result.putAll(offlineExpiry(paymentNo));
        result.put("message", "请到店完成付款，由工作人员确认收款后生效");
        return result;
    }

    private Map<String,Object> offlineExpiry(String paymentNo)
    {
        Object createdAt = jdbcTemplate.queryForObject(
                "select create_time from xy_payment where payment_no=?", Object.class, paymentNo);
        if (createdAt == null) throw new ServiceException("线下支付单创建时间缺失");
        return offlineExpiry(createdAt);
    }

    private Map<String,Object> offlineExpiry(Object createdAt)
    {
        LocalDateTime expireTime = dateTime(createdAt).plusMinutes(effectiveOfflinePaymentExpireMinutes());
        long seconds = Math.max(0L, ChronoUnit.SECONDS.between(LocalDateTime.now(), expireTime));
        Map<String,Object> result = new HashMap<>();
        result.put("expiresAutomatically", true);
        result.put("expiresInMinutes", (seconds + 59L) / 60L);
        result.put("expireTime", API_TIME.format(expireTime));
        return result;
    }

    /** 待收款和历史线下流水，供后台对账与显式收款。 */
    public List<Map<String,Object>> offlinePayments(String status)
    {
        String normalized = StringUtils.isEmpty(status) ? "PENDING" : status.trim().toUpperCase();
        if (!"ALL".equals(normalized) && !"PENDING".equals(normalized) && !"SUCCESS".equals(normalized)
                && !"CLOSED".equals(normalized) && !"REFUNDING".equals(normalized)
                && !"REFUNDED".equals(normalized))
            throw new ServiceException("线下流水状态不合法");
        String sql = offlinePaymentSelect() + " where p.channel='OFFLINE'";
        List<Object> args = new ArrayList<>();
        if (!"ALL".equals(normalized))
        {
            sql += " and p.status=?";
            args.add(normalized);
        }
        sql += " order by p.create_time desc,p.payment_id desc";
        List<Map<String,Object>> result = jdbcTemplate.queryForList(sql, args.toArray());
        for (Map<String,Object> payment : result)
        {
            if ("PENDING".equals(String.valueOf(payment.get("status"))) && payment.get("createTime") != null)
                payment.putAll(offlineExpiry(payment.get("createTime")));
        }
        return result;
    }

    /**
     * 只能由后台操作员在实际收到现金/POS/转账后调用。
     * 该操作幂等，且严格限制为 OFFLINE 渠道，不能用于绕过微信支付回调。
     */
    @Transactional
    public Map<String,Object> confirmOfflinePayment(String paymentNo, String operator)
    {
        if (StringUtils.isEmpty(paymentNo)) throw new ServiceException("线下支付单号不能为空");
        if (StringUtils.isEmpty(operator)) throw new ServiceException("收款操作人不能为空");
        List<Map<String,Object>> rows = jdbcTemplate.queryForList(
                "select payment_no,amount,channel,status,transaction_id,create_time from xy_payment where payment_no=? for update",
                paymentNo);
        if (rows.isEmpty()) throw new ServiceException("线下收款单不存在");
        Map<String,Object> row = rows.get(0);
        if (!PAYMENT_CHANNEL_OFFLINE.equals(String.valueOf(row.get("channel"))))
            throw new ServiceException("仅线下付款流水可由后台确认收款");
        if (!"PENDING".equals(String.valueOf(row.get("status"))) && !"SUCCESS".equals(String.valueOf(row.get("status"))))
            throw new ServiceException("该线下收款单已关闭或状态不可确认");
        if ("PENDING".equals(String.valueOf(row.get("status"))))
        {
            Object createdAt = row.get("create_time");
            if (createdAt == null || !LocalDateTime.now().isBefore(
                    dateTime(createdAt).plusMinutes(effectiveOfflinePaymentExpireMinutes())))
                throw new ServiceException("该线下收款申请已超时，请关闭后由用户重新提交");
        }
        String transactionId = row.get("transaction_id") == null
                ? "OFFLINE-" + nextNo("TX") : String.valueOf(row.get("transaction_id"));
        completePayment(paymentNo, transactionId, cents(row.get("amount")), PAYMENT_CHANNEL_OFFLINE);
        List<Map<String,Object>> result = jdbcTemplate.queryForList(offlinePaymentSelect() + " where p.payment_no=?", paymentNo);
        if (result.isEmpty()) throw new ServiceException("收款成功但流水查询失败");
        result.get(0).put("confirmedBy", operator);
        return result.get(0);
    }

    /** 后台关闭长期未到店的线下收款申请；商品单必须与用户取消一样归还库存。 */
    @Transactional
    public Map<String,Object> closeOfflinePayment(String paymentNo, String operator)
    {
        if (StringUtils.isEmpty(paymentNo)) throw new ServiceException("线下支付单号不能为空");
        if (StringUtils.isEmpty(operator)) throw new ServiceException("关闭操作人不能为空");
        List<Map<String,Object>> rows = jdbcTemplate.queryForList(
                "select payment_no,business_type,business_id,channel,status from xy_payment where payment_no=? for update",
                paymentNo);
        if (rows.isEmpty()) throw new ServiceException("线下收款单不存在");
        Map<String,Object> row = rows.get(0);
        if (!PAYMENT_CHANNEL_OFFLINE.equals(String.valueOf(row.get("channel"))))
            throw new ServiceException("仅线下待收款申请可手工关闭");
        if ("CLOSED".equals(String.valueOf(row.get("status"))))
            return closedOfflineResult(paymentNo, operator);
        if (!"PENDING".equals(String.valueOf(row.get("status"))))
            throw new ServiceException("已收款或退款中的流水不能关闭");
        Long businessId = ((Number) row.get("business_id")).longValue();
        String businessType = String.valueOf(row.get("business_type"));
        if ("ORDER".equals(businessType))
        {
            int closed = jdbcTemplate.update(
                    "update xy_order set status='CANCELED' where order_id=? and status='PENDING_PAYMENT'", businessId);
            if (closed != 1) throw new ServiceException("商品订单状态已变更，不能关闭");
            jdbcTemplate.update("update xy_product p join xy_order_item i on i.product_id=p.product_id set p.stock=p.stock+i.quantity where i.order_id=?", businessId);
        }
        else if ("MEMBERSHIP".equals(businessType))
        {
            int closed = jdbcTemplate.update(
                    "update xy_membership_order set status='CANCELED' where membership_order_id=? and status='PENDING_PAYMENT'",
                    businessId);
            if (closed != 1) throw new ServiceException("会员开卡订单状态已变更，不能关闭");
        }
        else throw new ServiceException("线下收款业务类型不合法");
        jdbcTemplate.update("update xy_payment set status='CLOSED' where payment_no=? and status='PENDING'", paymentNo);
        return closedOfflineResult(paymentNo, operator);
    }

    private Map<String,Object> closedOfflineResult(String paymentNo, String operator)
    {
        Map<String,Object> result = new HashMap<>();
        result.put("paymentNo", paymentNo);
        result.put("status", "CLOSED");
        result.put("closedBy", operator);
        return result;
    }

    private String offlinePaymentSelect()
    {
        return "select p.payment_no as paymentNo,p.business_type as businessType,p.amount,p.channel,p.status,"
                + "p.transaction_id as transactionId,p.paid_time as paidTime,p.create_time as createTime,"
                + "coalesce(o.order_no,mo.order_no) as businessOrderNo,coalesce(o.status,mo.status) as businessStatus,"
                + "(select a.after_sale_no from xy_after_sale a where a.order_id=o.order_id order by a.after_sale_id desc limit 1) as afterSaleNo,"
                + "(select case when a.status='RESTOCKED' then 'APPROVED' else a.status end from xy_after_sale a where a.order_id=o.order_id order by a.after_sale_id desc limit 1) as afterSaleStatus,"
                + "(select a.original_order_status from xy_after_sale a where a.order_id=o.order_id order by a.after_sale_id desc limit 1) as originalOrderStatus,"
                + "(select case when a.status='RESTOCKED' then 1 else 0 end from xy_after_sale a where a.order_id=o.order_id order by a.after_sale_id desc limit 1) as restocked,"
                + "(select case when a.status='APPROVED' and a.original_order_status in ('SHIPPED','COMPLETED') then 1 else 0 end from xy_after_sale a where a.order_id=o.order_id order by a.after_sale_id desc limit 1) as restockRequired,"
                + "m.member_id as memberId,m.nickname,m.mobile "
                + "from xy_payment p join xy_member m on m.member_id=p.member_id "
                + "left join xy_order o on p.business_type='ORDER' and o.order_id=p.business_id "
                + "left join xy_membership_order mo on p.business_type='MEMBERSHIP' and mo.membership_order_id=p.business_id";
    }

    private void activateMembership(Long membershipOrderId, String paymentNo)
    {
        List<Map<String,Object>> rows = jdbcTemplate.queryForList(
                "select o.member_id,o.plan_id,p.duration_days from xy_membership_order o join xy_membership_plan p on p.plan_id=o.plan_id where o.membership_order_id=? and o.status='PENDING_PAYMENT' for update",
                membershipOrderId);
        if (rows.isEmpty()) throw new ServiceException("会员订单状态异常，无法完成支付");
        Map<String,Object> order = rows.get(0);
        Long memberId = ((Number) order.get("member_id")).longValue();
        Long planId = ((Number) order.get("plan_id")).longValue();
        int days = ((Number) order.get("duration_days")).intValue();
        java.sql.Date max = jdbcTemplate.queryForObject(
                "select max(expire_date) from xy_membership_card where member_id=? and status='ACTIVE'", java.sql.Date.class, memberId);
        LocalDate start = max != null && !max.toLocalDate().isBefore(LocalDate.now())
                ? max.toLocalDate().plusDays(1) : LocalDate.now();
        jdbcTemplate.update("insert into xy_membership_card(member_id,plan_id,card_no,start_date,expire_date,source_payment_no) values(?,?,?,?,?,?)",
                memberId, planId, nextNo("MC"), start, start.plusDays(days - 1), paymentNo);
        jdbcTemplate.update("update xy_membership_order set status='PAID',paid_time=now() where membership_order_id=? and status='PENDING_PAYMENT'",
                membershipOrderId);
    }

    public List<Map<String,Object>> memberBills(Long memberId)
    {
        List<Map<String,Object>> bills = jdbcTemplate.queryForList(
                "select p.payment_no as paymentNo,p.business_type as businessType,p.amount,p.channel,p.status,p.paid_time as paidTime,p.create_time as createTime,coalesce(o.order_no,mo.order_no) as businessOrderNo,coalesce(o.status,mo.status) as businessStatus from xy_payment p left join xy_order o on p.business_type='ORDER' and o.order_id=p.business_id left join xy_membership_order mo on p.business_type='MEMBERSHIP' and mo.membership_order_id=p.business_id where p.member_id=? order by p.create_time desc",
                memberId);
        for (Map<String,Object> bill : bills)
        {
            if (PAYMENT_CHANNEL_OFFLINE.equals(String.valueOf(bill.get("channel")))
                    && "PENDING".equals(String.valueOf(bill.get("status")))
                    && bill.get("createTime") != null)
            {
                bill.put("expireTime", offlineExpiry(bill.get("createTime")).get("expireTime"));
            }
        }
        return bills;
    }

    public synchronized Map<String,Object> issueMemberVerifyCode(Long memberId)
    {
        if (currentCard(memberId) == null) throw new ServiceException("没有有效会员卡");
        String memberCodeKey = MEMBER_VERIFY_MEMBER_PREFIX + memberId;
        String previousCode = redisCache.getCacheObject(memberCodeKey);
        if (StringUtils.isNotEmpty(previousCode)) redisCache.deleteObject(MEMBER_VERIFY_PREFIX + previousCode);

        String code;
        do { code = randomDigits(8); } while (redisCache.getCacheObject(MEMBER_VERIFY_PREFIX + code) != null);
        redisCache.setCacheObject(MEMBER_VERIFY_PREFIX + code, memberId, MEMBER_VERIFY_EXPIRES_SECONDS, TimeUnit.SECONDS);
        redisCache.setCacheObject(memberCodeKey, code, MEMBER_VERIFY_EXPIRES_SECONDS, TimeUnit.SECONDS);

        Map<String,Object> result = new HashMap<>();
        result.put("code", code);
        result.put("expiresIn", MEMBER_VERIFY_EXPIRES_SECONDS);
        result.put("expiresAt", System.currentTimeMillis() + MEMBER_VERIFY_EXPIRES_SECONDS * 1000L);
        return result;
    }

    @Transactional
    public Map<String,Object> verifyMemberCode(String code,String operator)
    {
        Long memberId = redisCache.getCacheObject(MEMBER_VERIFY_PREFIX + code);
        if (memberId == null) throw new ServiceException("会员二维码无效或已过期");
        String memberCodeKey = MEMBER_VERIFY_MEMBER_PREFIX + memberId;
        String currentCode = redisCache.getCacheObject(memberCodeKey);
        if (!code.equals(currentCode)) throw new ServiceException("会员二维码已更新，请扫描最新二维码");

        Map<String,Object> member = memberProfile(memberId);
        jdbcTemplate.update("insert into xy_member_visit(member_id,verify_code,verified_by) values(?,?,?)",memberId,code,operator);
        redisCache.deleteObject(MEMBER_VERIFY_PREFIX + code);
        redisCache.deleteObject(memberCodeKey);
        return member;
    }

    @Transactional
    public void confirmReceipt(Long memberId, String orderNo)
    {
        int updated = jdbcTemplate.update("update xy_order set status='COMPLETED', received_time=now() where member_id=? and order_no=? and status='SHIPPED'", memberId, orderNo);
        if (updated != 1) throw new ServiceException("当前订单不能确认收货");
    }

    @Transactional
    public void cancelOrder(Long memberId,String orderNo)
    {
        List<Map<String,Object>> rows=jdbcTemplate.queryForList("select order_id from xy_order where member_id=? and order_no=? and status='PENDING_PAYMENT' for update",memberId,orderNo);
        if(rows.isEmpty())throw new ServiceException("当前订单不能取消");
        Long id=((Number)rows.get(0).get("order_id")).longValue();
        jdbcTemplate.update("update xy_order set status='CANCELED' where order_id=?",id);
        jdbcTemplate.update("update xy_product p join xy_order_item i on i.product_id=p.product_id set p.stock=p.stock+i.quantity where i.order_id=?",id);
        jdbcTemplate.update("update xy_payment set status='CLOSED' where business_type='ORDER' and business_id=? and status='PENDING'",id);
    }

    @Transactional
    public String createAfterSale(Long memberId, String orderNo, String reason, String description)
    {
        if (StringUtils.isEmpty(reason)) throw new ServiceException("请选择售后原因");
        checkLength(reason, 255, "售后原因不能超过255个字符");
        checkLength(description, 1000, "售后说明不能超过1000个字符");
        List<Map<String, Object>> orders = jdbcTemplate.queryForList("select order_id,status from xy_order where member_id = ? and order_no = ? and status in ('PAID','SHIPPED','COMPLETED') for update", memberId, orderNo);
        if (orders.isEmpty()) throw new ServiceException("当前订单不能申请售后");
        Long orderId = ((Number) orders.get(0).get("order_id")).longValue();
        Integer pending = jdbcTemplate.queryForObject("select count(1) from xy_after_sale where order_id=? and status in ('PENDING','REFUNDING','REFUND_FAILED')", Integer.class, orderId);
        if (pending != null && pending > 0) throw new ServiceException("该订单已有处理中售后单");
        String afterSaleNo = nextNo("AS");
        jdbcTemplate.update("insert into xy_after_sale(after_sale_no, order_id, member_id, reason, description_text, original_order_status) values (?, ?, ?, ?, ?, ?)", afterSaleNo, orderId, memberId, reason, description, orders.get(0).get("status"));
        return afterSaleNo;
    }

    public List<Map<String, Object>> adminMembers(String keyword)
    {
        String sql = "select m.member_id as memberId, m.nickname, m.mobile, m.invite_code as inviteCode, m.status, m.create_time as createTime, c.card_no as cardNo, c.expire_date as expireDate, c.status as cardStatus, inviter.nickname as inviterNickname, inviter.invite_code as inviterInviteCode from xy_member m left join xy_member inviter on inviter.member_id=m.inviter_member_id left join xy_membership_card c on c.card_id=(select c2.card_id from xy_membership_card c2 where c2.member_id=m.member_id order by c2.expire_date desc limit 1) where 1=1";
        List<Object> args = new ArrayList<>();
        if (StringUtils.isNotEmpty(keyword)) { sql += " and (m.nickname like ? or m.mobile like ? or m.invite_code like ?)"; args.add("%" + keyword + "%"); args.add("%" + keyword + "%"); args.add("%" + keyword + "%"); }
        sql += " order by m.create_time desc";
        return jdbcTemplate.queryForList(sql, args.toArray());
    }

    public List<Map<String, Object>> adminReservations(LocalDate date, String status)
    {
        String sql = "select r.reservation_id as reservationId, r.reservation_no as reservationNo, r.reservation_date as reservationDate, r.status, r.verify_code as verifyCode, m.nickname, m.mobile, se.seat_code as seatCode, se.zone_name as zoneName, date_format(s.start_time, '%H:%i') as startTime, date_format(s.end_time, '%H:%i') as endTime from xy_reservation r join xy_member m on m.member_id=r.member_id join xy_seat se on se.seat_id=r.seat_id join xy_reservation_slot s on s.slot_id=r.slot_id where r.reservation_date=?";
        List<Object> args = new ArrayList<>(); args.add(date);
        if (StringUtils.isNotEmpty(status)) { sql += " and r.status=?"; args.add(status); }
        sql += " order by s.start_time, se.sort_order";
        return jdbcTemplate.queryForList(sql, args.toArray());
    }

    @Transactional
    public void checkIn(String verifyCode)
    {
        int count = jdbcTemplate.update("update xy_reservation set status='CHECKED_IN', checkin_time=now() where verify_code=? and status='BOOKED' and reservation_date=curdate()", verifyCode);
        if (count != 1) throw new ServiceException("核销码无效、已核销或不在预约日期");
    }

    public List<Map<String, Object>> adminProducts()
    {
        return jdbcTemplate.queryForList("select product_id as productId, product_name as productName, category_name as categoryName, cover_url as coverUrl, detail_text as detailText, sale_price as salePrice, member_discount_enabled as memberDiscountEnabled, stock, status, sort_order as sortOrder from xy_product order by sort_order, product_id desc");
    }

    @Transactional
    public Long saveProduct(Map<String, Object> input)
    {
        String productName = required(input, "productName", "商品名称不能为空");
        String categoryName = required(input, "categoryName", "商品分类不能为空");
        checkLength(productName, 150, "商品名称不能超过150个字符");
        checkLength(categoryName, 64, "商品分类不能超过64个字符");
        String coverUrl = optionalText(input, "coverUrl", 500, "商品主图地址不能超过500个字符");
        String detailText = optionalText(input, "detailText", 65535, "商品详情过长");
        BigDecimal salePrice = decimal(input.get("salePrice"), "销售价不合法");
        Integer stock = integer(input.get("stock"), 0, 0, 2000000000, "商品库存不合法");
        validateMoney(salePrice, "商品价格不合法");
        int memberDiscountEnabled = Boolean.FALSE.equals(input.get("memberDiscountEnabled")) || "0".equals(String.valueOf(input.get("memberDiscountEnabled"))) ? 0 : 1;
        Long productId = number(input.get("productId"));
        String status = "1".equals(String.valueOf(input.get("status"))) ? "1" : "0";
        int sortOrder = integer(input.get("sortOrder"), 0, 0, 1000000, "商品排序值不合法");
        if (productId == null)
        {
            jdbcTemplate.update("insert into xy_product(product_name, category_name, cover_url, detail_text, sale_price, member_discount_enabled, stock, status, sort_order) values (?, ?, ?, ?, ?, ?, ?, ?, ?)", productName, categoryName, coverUrl, detailText, salePrice, memberDiscountEnabled, stock, status, sortOrder);
            return jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        }
        int count = jdbcTemplate.update("update xy_product set product_name=?, category_name=?, cover_url=?, detail_text=?, sale_price=?, member_discount_enabled=?, stock=?, status=?, sort_order=? where product_id=?", productName, categoryName, coverUrl, detailText, salePrice, memberDiscountEnabled, stock, status, sortOrder, productId);
        if (count != 1) throw new ServiceException("商品不存在");
        return productId;
    }

    public Map<String, Object> dashboard()
    {
        Map<String, Object> result = new HashMap<>();
        result.put("todayReservations", jdbcTemplate.queryForObject("select count(1) from xy_reservation where reservation_date=curdate() and seat_lock=1", Integer.class));
        result.put("activeMembers", jdbcTemplate.queryForObject("select count(distinct member_id) from xy_membership_card where status='ACTIVE' and start_date<=curdate() and expire_date>=curdate()", Integer.class));
        result.put("pendingOrders", jdbcTemplate.queryForObject("select count(1) from xy_order where status='PENDING_PAYMENT'", Integer.class));
        result.put("todayPaidAmount", jdbcTemplate.queryForObject("select coalesce(sum(paid_amount),0) from xy_order where date(paid_time)=curdate() and status in ('PAID','SHIPPED','COMPLETED')", BigDecimal.class));
        return result;
    }

    public List<Map<String, Object>> financeRecords()
    {
        return jdbcTemplate.queryForList("select p.payment_no as paymentNo, p.business_type as businessType, p.amount, p.channel, p.status, p.transaction_id as transactionId, p.paid_time as paidTime, p.create_time as createTime, m.nickname, m.mobile from xy_payment p join xy_member m on m.member_id=p.member_id order by p.create_time desc");
    }

    public List<Map<String,Object>> adminOrders()
    {
        List<Map<String,Object>> orders = jdbcTemplate.queryForList(
                "select o.order_id as orderId,o.order_no as orderNo,o.delivery_type as deliveryType,"
                        + "o.total_amount as totalAmount,o.discount_amount as discountAmount,"
                        + "o.member_discount_rate as memberDiscountRate,o.payable_amount as payableAmount,"
                        + "o.paid_amount as paidAmount,o.receiver_snapshot as receiverSnapshot,o.status,"
                        + "o.create_time as createTime,m.nickname,m.mobile,"
                        + "(select p.payment_no from xy_payment p where p.business_type='ORDER' and p.business_id=o.order_id order by p.payment_id desc limit 1) as paymentNo,"
                        + "(select p.channel from xy_payment p where p.business_type='ORDER' and p.business_id=o.order_id order by p.payment_id desc limit 1) as paymentChannel "
                        + "from xy_order o join xy_member m on m.member_id=o.member_id order by o.create_time desc");
        if (orders.isEmpty()) return orders;

        Map<Long, List<Map<String,Object>>> itemsByOrder = new HashMap<>();
        List<Object> orderIds = new ArrayList<>();
        for (Map<String,Object> order : orders)
        {
            Long orderId = ((Number) order.get("orderId")).longValue();
            orderIds.add(orderId);
            itemsByOrder.put(orderId, new ArrayList<>());
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(orderIds.size(), "?"));
        List<Map<String,Object>> items = jdbcTemplate.queryForList(
                "select order_id as orderId,product_id as productId,product_name as productName,"
                        + "cover_url as coverUrl,sale_price as salePrice,quantity,subtotal_amount as subtotalAmount "
                        + "from xy_order_item where order_id in (" + placeholders + ") order by order_id,item_id",
                orderIds.toArray());
        for (Map<String,Object> item : items)
        {
            Long orderId = ((Number) item.get("orderId")).longValue();
            List<Map<String,Object>> orderItems = itemsByOrder.get(orderId);
            if (orderItems != null) orderItems.add(item);
        }
        for (Map<String,Object> order : orders)
        {
            Long orderId = ((Number) order.get("orderId")).longValue();
            order.put("items", itemsByOrder.get(orderId));
        }
        return orders;
    }
    @Transactional public void shipOrder(String orderNo){int count=jdbcTemplate.update("update xy_order set status='SHIPPED',shipped_time=now() where order_no=? and status='PAID'",orderNo);if(count!=1)throw new ServiceException("当前订单不能发货");}
    public List<Map<String,Object>> adminAfterSales()
    {
        return jdbcTemplate.queryForList(
                "select a.after_sale_no as afterSaleNo,a.reason,a.description_text as description,"
                        + "case when a.status='RESTOCKED' then 'APPROVED' else a.status end as status,"
                        + "a.original_order_status as originalOrderStatus,a.refund_no as refundNo,a.refund_id as refundId,"
                        + "case when a.status='RESTOCKED' then 1 else 0 end as restocked,"
                        + "case when a.status='APPROVED' and a.original_order_status in ('SHIPPED','COMPLETED') then 1 else 0 end as restockRequired,"
                        + "a.create_time as createTime,o.order_no as orderNo,o.paid_amount as paidAmount,"
                        + "p.payment_no as paymentNo,p.channel as paymentChannel,m.nickname,m.mobile "
                        + "from xy_after_sale a join xy_order o on o.order_id=a.order_id join xy_member m on m.member_id=a.member_id "
                        + "left join xy_payment p on p.business_type='ORDER' and p.business_id=o.order_id order by a.create_time desc");
    }
    public void rejectAfterSale(String afterSaleNo){int count=jdbcTemplate.update("update xy_after_sale set status='REJECTED' where after_sale_no=? and status='PENDING'",afterSaleNo);if(count!=1)throw new ServiceException("售后单当前不能拒绝");}
    @Transactional
    public void approveAfterSale(String afterSaleNo, XyWechatPayService payService)
    {
        List<Map<String,Object>> rows = jdbcTemplate.queryForList(
                "select a.order_id,o.paid_amount,p.transaction_id,p.channel from xy_after_sale a join xy_order o on o.order_id=a.order_id join xy_payment p on p.business_type='ORDER' and p.business_id=o.order_id and p.status='SUCCESS' where a.after_sale_no=? and a.status in ('PENDING','REFUND_FAILED') for update",
                afterSaleNo);
        if (rows.isEmpty()) throw new ServiceException("退款申请或成功支付记录不存在");
        Map<String,Object> row = rows.get(0);
        int fen = new BigDecimal(row.get("paid_amount").toString()).movePointRight(2).intValueExact();
        // 商户退款单号必须在重试间保持不变：若微信已受理而本地事务提交失败，
        // 再次审批会以同一 out_refund_no 查询/续办，避免生成第二笔真实退款。
        String refundNo = "RF" + afterSaleNo;
        String channel = String.valueOf(row.get("channel"));
        String refundId = null;
        if (PAYMENT_CHANNEL_DEMO.equals(channel))
        {
            refundId = "DEMO-" + nextNo("RID");
        }
        else if (PAYMENT_CHANNEL_WECHAT.equals(channel))
        {
            Map<String,Object> refund = payService.refund(String.valueOf(row.get("transaction_id")), refundNo, fen, fen);
            refundId = String.valueOf(refund.get("refund_id"));
        }
        else if (!PAYMENT_CHANNEL_OFFLINE.equals(channel))
        {
            throw new ServiceException("该支付渠道不支持退款");
        }
        jdbcTemplate.update("update xy_after_sale set status='REFUNDING',refund_no=?,refund_id=? where after_sale_no=?", refundNo, refundId, afterSaleNo);
        jdbcTemplate.update("update xy_payment set status='REFUNDING' where business_type='ORDER' and business_id=?", row.get("order_id"));
        jdbcTemplate.update("update xy_order set status='AFTER_SALE' where order_id=?", row.get("order_id"));
        if (PAYMENT_CHANNEL_DEMO.equals(channel)) completeRefund(refundNo, refundId);
    }

    /** 线下退款必须在工作人员实际退款后显式完成，审批本身不会自动标记退款成功。 */
    @Transactional
    public void completeOfflineRefund(String afterSaleNo)
    {
        List<Map<String,Object>> rows = jdbcTemplate.queryForList(
                "select a.refund_no,a.status,p.channel,p.status as payment_status from xy_after_sale a join xy_payment p on p.business_type='ORDER' and p.business_id=a.order_id where a.after_sale_no=? and a.status in ('REFUNDING','APPROVED','RESTOCKED') for update",
                afterSaleNo);
        if (rows.isEmpty()) throw new ServiceException("线下退款单不存在或尚未审批");
        Map<String,Object> row = rows.get(0);
        if (!PAYMENT_CHANNEL_OFFLINE.equals(String.valueOf(row.get("channel"))))
            throw new ServiceException("仅线下支付订单可手工确认退款");
        if (("APPROVED".equals(String.valueOf(row.get("status")))
                || "RESTOCKED".equals(String.valueOf(row.get("status"))))
                && "REFUNDED".equals(String.valueOf(row.get("payment_status"))))
            return;
        String refundNo = String.valueOf(row.get("refund_no"));
        if (StringUtils.isEmpty(refundNo) || "null".equals(refundNo)) throw new ServiceException("线下退款单号缺失");
        completeRefund(refundNo, "OFFLINE-" + nextNo("RID"));
    }
    @Transactional
    public void completeRefund(String refundNo, String refundId)
    {
        List<Map<String,Object>> rows = jdbcTemplate.queryForList(
                "select order_id,status,original_order_status from xy_after_sale where refund_no=? and status in('REFUNDING','APPROVED','RESTOCKED') for update",
                refundNo);
        if (rows.isEmpty() || "APPROVED".equals(rows.get(0).get("status"))
                || "RESTOCKED".equals(rows.get(0).get("status"))) return;
        Map<String,Object> row = rows.get(0);
        Long orderId = ((Number) row.get("order_id")).longValue();
        String originalStatus = String.valueOf(row.get("original_order_status"));
        boolean autoRestock = "PAID".equals(originalStatus);
        jdbcTemplate.update("update xy_after_sale set status=?,refund_id=? where refund_no=?",
                autoRestock ? "RESTOCKED" : "APPROVED", refundId, refundNo);
        jdbcTemplate.update("update xy_payment set status='REFUNDED' where business_type='ORDER' and business_id=?", orderId);
        jdbcTemplate.update("update xy_order set status='REFUNDED' where order_id=?", orderId);
        // 未发货订单可直接回库；已发货/已签收只退款，必须待仓库实际收到退货后显式回库。
        if (autoRestock)
            jdbcTemplate.update("update xy_product p join xy_order_item i on i.product_id=p.product_id set p.stock=p.stock+i.quantity where i.order_id=?", orderId);
    }

    /** 已发货/已签收退款在仓库实际收到退货后回库；通过售后状态实现原子幂等。 */
    @Transactional
    public void restockReturnedAfterSale(String afterSaleNo)
    {
        List<Map<String,Object>> rows = jdbcTemplate.queryForList(
                "select order_id,status,original_order_status from xy_after_sale where after_sale_no=? and status in ('APPROVED','RESTOCKED') for update",
                afterSaleNo);
        if (rows.isEmpty()) throw new ServiceException("售后单尚未退款完成，不能回库");
        Map<String,Object> row = rows.get(0);
        if ("RESTOCKED".equals(String.valueOf(row.get("status")))) return;
        String originalStatus = String.valueOf(row.get("original_order_status"));
        if (!"SHIPPED".equals(originalStatus) && !"COMPLETED".equals(originalStatus))
            throw new ServiceException("该售后单无需手工退货回库");
        Long orderId = ((Number) row.get("order_id")).longValue();
        int updated = jdbcTemplate.update(
                "update xy_after_sale set status='RESTOCKED' where after_sale_no=? and status='APPROVED'",
                afterSaleNo);
        if (updated != 1) throw new ServiceException("退货回库状态已变更，请刷新后重试");
        jdbcTemplate.update(
                "update xy_product p join xy_order_item i on i.product_id=p.product_id set p.stock=p.stock+i.quantity where i.order_id=?",
                orderId);
    }
    @Transactional public void failRefund(String refundNo,String refundId){List<Map<String,Object>> rows=jdbcTemplate.queryForList("select order_id,original_order_status from xy_after_sale where refund_no=? and status='REFUNDING' for update",refundNo);if(rows.isEmpty())return;Map<String,Object> row=rows.get(0);Long orderId=((Number)row.get("order_id")).longValue();String originalStatus=StringUtils.isEmpty((String)row.get("original_order_status"))?"COMPLETED":String.valueOf(row.get("original_order_status"));jdbcTemplate.update("update xy_after_sale set status='REFUND_FAILED',refund_id=? where refund_no=?",refundId,refundNo);jdbcTemplate.update("update xy_payment set status='SUCCESS' where business_type='ORDER' and business_id=?",orderId);jdbcTemplate.update("update xy_order set status=? where order_id=?",originalStatus,orderId);}

    public List<Map<String, Object>> staffMembers()
    {
        return jdbcTemplate.queryForList("select u.user_id as userId, u.user_name as userName, u.nick_name as nickName, u.phonenumber, u.status, d.dept_name as deptName from sys_user u left join sys_dept d on d.dept_id=u.dept_id where u.del_flag='0' order by u.user_id");
    }

    public List<Map<String, Object>> verificationRecords()
    {
        return jdbcTemplate.queryForList("select r.reservation_no as reservationNo, r.verify_code as verifyCode, r.reservation_date as reservationDate, r.status, r.checkin_time as checkinTime, m.nickname, m.mobile, se.seat_code as seatCode, date_format(s.start_time,'%H:%i') as startTime, date_format(s.end_time,'%H:%i') as endTime from xy_reservation r join xy_member m on m.member_id=r.member_id join xy_seat se on se.seat_id=r.seat_id join xy_reservation_slot s on s.slot_id=r.slot_id order by r.reservation_date desc,s.start_time desc");
    }

    public Map<String, Object> reservationConfiguration()
    {
        Map<String, Object> result = new HashMap<>();
        result.put("stores", jdbcTemplate.queryForList("select store_id as storeId, store_name as storeName, address, phone, longitude, latitude, business_hours as businessHours, status from xy_store order by store_id"));
        result.put("slots", jdbcTemplate.queryForList("select slot_id as slotId, store_id as storeId, date_format(start_time, '%H:%i') as startTime, date_format(end_time, '%H:%i') as endTime, status, sort_order as sortOrder from xy_reservation_slot order by store_id, sort_order, start_time"));
        result.put("seats", jdbcTemplate.queryForList("select seat_id as seatId, store_id as storeId, seat_code as seatCode, zone_name as zoneName, status, sort_order as sortOrder from xy_seat order by store_id, sort_order, seat_code"));
        result.put("plans", jdbcTemplate.queryForList("select plan_id as planId, plan_name as planName, amount, duration_days as durationDays, daily_reservation_limit as dailyReservationLimit, status, sort_order as sortOrder from xy_membership_plan order by sort_order, plan_id"));
        return result;
    }

    @Transactional
    public Long saveStore(Map<String, Object> input)
    {
        String storeName = required(input, "storeName", "门店名称不能为空");
        String address = required(input, "address", "门店地址不能为空");
        String phone = required(input, "phone", "门店电话不能为空");
        String businessHours = required(input, "businessHours", "营业时间不能为空");
        checkLength(storeName, 100, "门店名称不能超过100个字符");
        checkLength(address, 255, "门店地址不能超过255个字符");
        checkLength(phone, 32, "门店电话不能超过32个字符");
        checkLength(businessHours, 100, "营业时间说明不能超过100个字符");
        BigDecimal longitude = optionalDecimal(input.get("longitude"), "门店经度不合法");
        BigDecimal latitude = optionalDecimal(input.get("latitude"), "门店纬度不合法");
        if ((longitude == null) != (latitude == null))
            throw new ServiceException("门店经纬度必须同时填写或同时留空");
        if (longitude != null && (longitude.compareTo(new BigDecimal("-180")) < 0 || longitude.compareTo(new BigDecimal("180")) > 0))
            throw new ServiceException("门店经度必须在 -180 到 180 之间");
        if (latitude != null && (latitude.compareTo(new BigDecimal("-90")) < 0 || latitude.compareTo(new BigDecimal("90")) > 0))
            throw new ServiceException("门店纬度必须在 -90 到 90 之间");
        if (longitude != null && (decimalScale(longitude) > 7 || decimalScale(latitude) > 7))
            throw new ServiceException("门店经纬度最多保留7位小数");
        Long storeId = number(input.get("storeId"));
        String status = "1".equals(String.valueOf(input.get("status"))) ? "1" : "0";
        if (storeId == null)
        {
            jdbcTemplate.update("insert into xy_store(store_name,address,phone,longitude,latitude,business_hours,status) values(?,?,?,?,?,?,?)", storeName, address, phone, longitude, latitude, businessHours, status);
            return jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        }
        int count = jdbcTemplate.update("update xy_store set store_name=?,address=?,phone=?,longitude=?,latitude=?,business_hours=?,status=? where store_id=?", storeName, address, phone, longitude, latitude, businessHours, status, storeId);
        if (count != 1) throw new ServiceException("门店不存在");
        return storeId;
    }

    @Transactional
    public Long saveSlot(Map<String, Object> input)
    {
        Long storeId = number(input.get("storeId"));
        Long slotId = number(input.get("slotId"));
        String startTime = required(input, "startTime", "开始时间不能为空");
        String endTime = required(input, "endTime", "结束时间不能为空");
        if (storeId == null || !startTime.matches("^([01]\\d|2[0-3]):[0-5]\\d$") || !endTime.matches("^([01]\\d|2[0-3]):[0-5]\\d$") || startTime.compareTo(endTime) >= 0) throw new ServiceException("时段参数不合法");
        String status = "1".equals(String.valueOf(input.get("status"))) ? "1" : "0";
        int sortOrder = integer(input.get("sortOrder"), 0, 0, 1000000, "时段排序值不合法");
        List<Long> lockedStores = jdbcTemplate.queryForList("select store_id from xy_store where store_id=? for update", Long.class, storeId);
        if (lockedStores.isEmpty()) throw new ServiceException("门店不存在");
        if ("0".equals(status))
        {
            Integer overlaps = slotId == null
                    ? jdbcTemplate.queryForObject("select count(1) from xy_reservation_slot where store_id=? and status='0' and start_time < ? and end_time > ?", Integer.class, storeId, endTime, startTime)
                    : jdbcTemplate.queryForObject("select count(1) from xy_reservation_slot where store_id=? and status='0' and slot_id<>? and start_time < ? and end_time > ?", Integer.class, storeId, slotId, endTime, startTime);
            if (overlaps != null && overlaps > 0) throw new ServiceException("该时段与现有可用预约时段重叠");
        }
        try
        {
            if (slotId == null)
            {
                jdbcTemplate.update("insert into xy_reservation_slot(store_id,start_time,end_time,status,sort_order) values(?,?,?,?,?)", storeId, startTime, endTime, status, sortOrder);
                return jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
            }
            int count = jdbcTemplate.update("update xy_reservation_slot set start_time=?,end_time=?,status=?,sort_order=? where slot_id=? and store_id=?", startTime, endTime, status, sortOrder, slotId, storeId);
            if (count != 1) throw new ServiceException("时段不存在");
            return slotId;
        }
        catch (DuplicateKeyException ex) { throw new ServiceException("该门店已存在相同时段"); }
    }

    @Transactional
    public Long saveSeat(Map<String, Object> input)
    {
        Long storeId = number(input.get("storeId"));
        Long seatId = number(input.get("seatId"));
        String seatCode = required(input, "seatCode", "座位编号不能为空");
        checkLength(seatCode, 32, "座位编号不能超过32个字符");
        String zoneName = optionalText(input, "zoneName", 32, "区域名称不能超过32个字符");
        if (storeId == null) throw new ServiceException("门店不能为空");
        String status = "1".equals(String.valueOf(input.get("status"))) ? "1" : "0";
        int sortOrder = integer(input.get("sortOrder"), 0, 0, 1000000, "座位排序值不合法");
        List<Long> lockedStores = jdbcTemplate.queryForList("select store_id from xy_store where store_id=? for update", Long.class, storeId);
        if (lockedStores.isEmpty()) throw new ServiceException("门店不存在");
        try
        {
            if (seatId == null)
            {
                jdbcTemplate.update("insert into xy_seat(store_id,seat_code,zone_name,status,sort_order) values(?,?,?,?,?)", storeId, seatCode, zoneName, status, sortOrder);
                return jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
            }
            int count = jdbcTemplate.update("update xy_seat set seat_code=?,zone_name=?,status=?,sort_order=? where seat_id=? and store_id=?", seatCode, zoneName, status, sortOrder, seatId, storeId);
            if (count != 1) throw new ServiceException("座位不存在");
            return seatId;
        }
        catch (DuplicateKeyException ex) { throw new ServiceException("该门店已存在相同座位编号"); }
    }

    @Transactional
    public Long savePlan(Map<String, Object> input)
    {
        String planName = required(input, "planName", "套餐名称不能为空");
        checkLength(planName, 100, "套餐名称不能超过100个字符");
        Long planId = number(input.get("planId"));
        BigDecimal amount = decimal(input.get("amount"), "套餐金额不合法");
        Integer durationDays = input.get("durationDays") == null ? null
                : integer(input.get("durationDays"), 0, 1, 3650, "套餐有效天数不合法");
        Integer dailyLimit = integer(input.get("dailyReservationLimit"), 1, 1, 100, "每日预约上限不合法");
        validateMoney(amount, "套餐金额不合法");
        if (durationDays == null || durationDays != 30 || dailyLimit != 1)
            throw new ServiceException("目前仅支持30天包月会员，基础预约上限必须为1次");
        String status = "1".equals(String.valueOf(input.get("status"))) ? "1" : "0";
        int sortOrder = integer(input.get("sortOrder"), 0, 0, 1000000, "套餐排序值不合法");
        jdbcTemplate.queryForList("select plan_id from xy_membership_plan where duration_days=30 for update", Long.class);
        if ("0".equals(status))
        {
            if (planId == null) jdbcTemplate.update("update xy_membership_plan set status='1' where duration_days=30 and status='0'");
            else jdbcTemplate.update("update xy_membership_plan set status='1' where duration_days=30 and status='0' and plan_id<>?", planId);
        }
        if (planId == null)
        {
            jdbcTemplate.update("insert into xy_membership_plan(plan_name,amount,duration_days,daily_reservation_limit,status,sort_order) values(?,?,?,?,?,?)", planName, amount, durationDays, dailyLimit, status, sortOrder);
            return jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        }
        int count = jdbcTemplate.update("update xy_membership_plan set plan_name=?,amount=?,duration_days=?,daily_reservation_limit=?,status=?,sort_order=? where plan_id=?", planName, amount, durationDays, dailyLimit, status, sortOrder, planId);
        if (count != 1) throw new ServiceException("套餐不存在");
        return planId;
    }

    private String generateInviteCode()
    {
        for (int i = 0; i < 10; i++)
        {
            String code = randomDigits(6);
            Integer count = jdbcTemplate.queryForObject("select count(1) from xy_member where invite_code=?", Integer.class, code);
            if (count == null || count == 0) return code;
        }
        throw new ServiceException("邀请码生成失败，请重试");
    }

    private void validateReservationDate(LocalDate reservationDate)
    {
        LocalDate today = LocalDate.now();
        if (reservationDate == null || reservationDate.isBefore(today))
            throw new ServiceException("不能预约过去的日期");
        if (reservationDate.isAfter(today.plusDays(RESERVATION_WINDOW_DAYS - 1L)))
            throw new ServiceException("仅可预约未来30天内的场次");
    }

    private String nextNo(String prefix)
    {
        return prefix + NO_TIME.format(LocalDateTime.now()) + randomDigits(4);
    }

    private String randomDigits(int length)
    {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) value.append(RANDOM.nextInt(10));
        return value.toString();
    }

    private String required(Map<String, Object> input, String key, String message)
    {
        Object value = input.get(key);
        if (value == null || StringUtils.isEmpty(String.valueOf(value).trim())) throw new ServiceException(message);
        return String.valueOf(value).trim();
    }

    private String optionalText(Map<String, Object> input, String key, int maxLength, String message)
    {
        Object value = input.get(key);
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return null;
        checkLength(text, maxLength, message);
        return text;
    }

    private void checkLength(String value, int maxLength, String message)
    {
        if (value != null && value.length() > maxLength) throw new ServiceException(message);
    }

    private Long number(Object value)
    {
        if (value == null || StringUtils.isEmpty(String.valueOf(value))) return null;
        try { return Long.valueOf(String.valueOf(value)); } catch (NumberFormatException ex) { throw new ServiceException("数字参数不合法"); }
    }

    private BigDecimal decimal(Object value, String message)
    {
        try { return new BigDecimal(String.valueOf(value)); } catch (Exception ex) { throw new ServiceException(message); }
    }

    private BigDecimal optionalDecimal(Object value, String message)
    {
        if (value == null || StringUtils.isEmpty(String.valueOf(value).trim())) return null;
        return decimal(value, message);
    }

    private int integer(Object value, int defaultValue, int minimum, int maximum, String message)
    {
        if (value == null || StringUtils.isEmpty(String.valueOf(value).trim())) return defaultValue;
        try
        {
            long parsed = Long.parseLong(String.valueOf(value).trim());
            if (parsed < minimum || parsed > maximum) throw new NumberFormatException();
            return (int) parsed;
        }
        catch (NumberFormatException ex) { throw new ServiceException(message); }
    }

    private void validateMoney(BigDecimal amount, String message)
    {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX_PAYMENT_AMOUNT) > 0 || decimalScale(amount) > 2)
            throw new ServiceException(message + "（必须大于0、最多2位小数且不超过" + MAX_PAYMENT_AMOUNT.toPlainString() + "）");
    }

    private int decimalScale(BigDecimal value)
    {
        return Math.max(0, value.stripTrailingZeros().scale());
    }

    private LocalDateTime dateTime(Object value)
    {
        if (value instanceof LocalDateTime) return (LocalDateTime) value;
        if (value instanceof java.sql.Timestamp) return ((java.sql.Timestamp) value).toLocalDateTime();
        if (value instanceof java.util.Date)
            return LocalDateTime.ofInstant(((java.util.Date) value).toInstant(), java.time.ZoneId.systemDefault());
        try { return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T')); }
        catch (Exception ex) { throw new ServiceException("支付单时间不合法"); }
    }

    private int cents(Object amount)
    {
        try { return new BigDecimal(String.valueOf(amount)).movePointRight(2).intValueExact(); }
        catch (Exception ex) { throw new ServiceException("支付金额不合法"); }
    }
}
