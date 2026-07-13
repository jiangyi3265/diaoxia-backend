package com.ruoyi.web.service.xy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
    private static final String MEMBER_PRODUCT_DISCOUNT_RATE_KEY = "member_product_discount_rate";
    private static final BigDecimal DEFAULT_MEMBER_PRODUCT_DISCOUNT_RATE = new BigDecimal("0.95");
    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final RedisCache redisCache;

    @Value("${xy.order-expire-minutes:30}")
    private int orderExpireMinutes;

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
                "select order_id from xy_order where status='PENDING_PAYMENT' and create_time < date_sub(now(), interval "
                        + expireMinutes + " minute) order by order_id limit 100 for update");
        for (Map<String, Object> row : expiredOrders)
        {
            Long orderId = ((Number) row.get("order_id")).longValue();
            int closed = jdbcTemplate.update("update xy_order set status='CANCELED' where order_id=? and status='PENDING_PAYMENT'", orderId);
            if (closed == 1)
            {
                jdbcTemplate.update("update xy_product p join xy_order_item i on i.product_id=p.product_id set p.stock=p.stock+i.quantity where i.order_id=?", orderId);
                jdbcTemplate.update("update xy_payment set status='CLOSED' where business_type='ORDER' and business_id=? and status='PENDING'", orderId);
            }
        }
        jdbcTemplate.update("update xy_membership_order o join xy_payment p on p.business_type='MEMBERSHIP' and p.business_id=o.membership_order_id set o.status='CANCELED',p.status='CLOSED' where o.status='PENDING_PAYMENT' and p.status='PENDING' and o.create_time < date_sub(now(), interval " + expireMinutes + " minute)");
        jdbcTemplate.update("update xy_reservation r join xy_reservation_slot s on s.slot_id=r.slot_id set r.status='NO_SHOW',r.seat_lock=null where r.status='BOOKED' and (r.reservation_date<curdate() or (r.reservation_date=curdate() and s.end_time<curtime()))");
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
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "select member_id, nickname, avatar_url from xy_member where openid = ?", openid);
        Long memberId;
        if (existing.isEmpty())
        {
            String inviteCode = generateInviteCode();
            jdbcTemplate.update("insert into xy_member(openid, unionid, invite_code) values (?, ?, ?)", openid, unionid, inviteCode);
            memberId = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        }
        else
        {
            memberId = ((Number) existing.get(0).get("member_id")).longValue();
            jdbcTemplate.update("update xy_member set unionid = coalesce(?, unionid) where member_id = ?", unionid, memberId);
        }
        String token = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        redisCache.setCacheObject(MEMBER_TOKEN_PREFIX + token, memberId, 30, TimeUnit.DAYS);
        Map<String, Object> result = new HashMap<>();
        result.put("memberToken", token);
        result.put("member", memberProfile(memberId));
        return result;
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
        List<Map<String, Object>> cards = jdbcTemplate.queryForList("select c.card_id as cardId, c.card_no as cardNo, c.start_date as startDate, c.expire_date as expireDate, c.status, c.usage_count as usageCount, p.plan_name as planName, p.daily_reservation_limit as dailyReservationLimit from xy_membership_card c join xy_membership_plan p on p.plan_id = c.plan_id where c.member_id = ? and c.status = 'ACTIVE' and c.expire_date >= curdate() order by c.expire_date desc limit 1", memberId);
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
        return jdbcTemplate.queryForList("select plan_id as planId, plan_name as planName, amount, duration_days as durationDays, daily_reservation_limit as dailyReservationLimit from xy_membership_plan where status='0' order by sort_order, plan_id");
    }

    public Map<String, Object> reservationAvailability(Long storeId, LocalDate reservationDate)
    {
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
        result.put("sameDayRolloverRule", "完成到店签到后，可在本人当日场次结束前10分钟起追加预约当日后续有空位时段");
        return result;
    }

    @Transactional
    public Map<String, Object> createReservation(Long memberId, Long storeId, Long slotId, Long seatId, LocalDate reservationDate)
    {
        if (reservationDate.isBefore(LocalDate.now()))
        {
            throw new ServiceException("不能预约过去的日期");
        }
        Map<String, Object> card = currentCard(memberId);
        if (card == null)
        {
            throw new ServiceException("请先开通有效会员卡");
        }
        List<Map<String, Object>> slotRows = jdbcTemplate.queryForList("select start_time,end_time from xy_reservation_slot where slot_id=? and store_id=? and status='0'", slotId, storeId);
        if (slotRows.isEmpty()) throw new ServiceException("选择的时段不可用");
        LocalTime targetStart = ((java.sql.Time) slotRows.get(0).get("start_time")).toLocalTime();
        if (reservationDate.equals(LocalDate.now()) && !LocalTime.now().isBefore(targetStart))
        {
            throw new ServiceException("当天只能预约尚未开始的时段");
        }
        Integer dailyLimit = ((Number) card.get("dailyReservationLimit")).intValue();
        Integer used = jdbcTemplate.queryForObject("select count(1) from xy_reservation where member_id = ? and reservation_date = ? and seat_lock = 1", Integer.class, memberId, reservationDate);
        boolean rolloverAllowed = hasSameDayRolloverPrivilege(memberId, reservationDate);
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

    /** 签到会员在本人场次结束前 10 分钟，可忽略套餐日预约上限追加当日后续空位。 */
    private boolean hasSameDayRolloverPrivilege(Long memberId, LocalDate reservationDate)
    {
        if (!LocalDate.now().equals(reservationDate)) return false;
        Integer count = jdbcTemplate.queryForObject(
                "select count(1) from xy_reservation r join xy_reservation_slot s on s.slot_id=r.slot_id "
                        + "where r.member_id=? and r.reservation_date=curdate() and r.status='CHECKED_IN' "
                        + "and curtime()>=subtime(s.end_time,'00:10:00') and curtime()<s.end_time",
                Integer.class, memberId);
        return count != null && count > 0;
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
        int affected = jdbcTemplate.update("update xy_reservation set status = 'CANCELED', seat_lock = null, cancel_time = now() where member_id = ? and reservation_no = ? and status = 'BOOKED' and reservation_date >= curdate()", memberId, reservationNo);
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
        int quantity = number(input.get("quantity")) == null ? 1 : number(input.get("quantity")).intValue();
        String deliveryType = required(input, "deliveryType", "请指定配送方式");
        if (productId == null || quantity < 1 || quantity > 99) throw new ServiceException("商品数量不合法");
        if (!"DELIVERY".equals(deliveryType) && !"PICKUP".equals(deliveryType)) throw new ServiceException("配送方式不合法");
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
        return jdbcTemplate.queryForList("select o.order_id as orderId, o.order_no as orderNo, o.delivery_type as deliveryType, o.payable_amount as payableAmount, o.status, o.create_time as createTime, (select product_name from xy_order_item i where i.order_id=o.order_id order by item_id limit 1) as productName, (select cover_url from xy_order_item i where i.order_id=o.order_id order by item_id limit 1) as coverUrl, (select sum(quantity) from xy_order_item i where i.order_id=o.order_id) as quantity from xy_order o where o.member_id = ? order by o.create_time desc", memberId);
    }

    public Map<String, Object> orderDetail(Long memberId, String orderNo)
    {
        List<Map<String, Object>> orders = jdbcTemplate.queryForList("select order_id as orderId, order_no as orderNo, delivery_type as deliveryType, total_amount as totalAmount, discount_amount as discountAmount, member_discount_rate as memberDiscountRate, freight_amount as freightAmount, payable_amount as payableAmount, paid_amount as paidAmount, status, receiver_snapshot as receiverSnapshot, create_time as createTime from xy_order where member_id = ? and order_no = ?", memberId, orderNo);
        if (orders.isEmpty()) throw new ServiceException("订单不存在");
        Map<String, Object> order = orders.get(0);
        order.put("items", jdbcTemplate.queryForList("select product_id as productId, product_name as productName, cover_url as coverUrl, sale_price as salePrice, quantity, subtotal_amount as subtotalAmount from xy_order_item where order_id = ?", order.get("orderId")));
        return order;
    }

    @Transactional
    public Map<String,Object> createOrderPayment(Long memberId, String orderNo, XyWechatPayService payService)
    {
        List<Map<String,Object>> list=jdbcTemplate.queryForList("select o.order_id,o.payable_amount,m.openid from xy_order o join xy_member m on m.member_id=o.member_id where o.member_id=? and o.order_no=? and o.status='PENDING_PAYMENT'",memberId,orderNo);
        if(list.isEmpty())throw new ServiceException("当前订单不能支付"); Map<String,Object> row=list.get(0); Long orderId=((Number)row.get("order_id")).longValue(); String paymentNo=nextNo("PY");
        List<Map<String,Object>> existing=jdbcTemplate.queryForList("select payment_no from xy_payment where business_type='ORDER' and business_id=? and status='PENDING'",orderId); if(!existing.isEmpty())paymentNo=String.valueOf(existing.get(0).get("payment_no")); else jdbcTemplate.update("insert into xy_payment(payment_no,member_id,business_type,business_id,amount,channel) values(?,?,?,?,?,?)",paymentNo,memberId,"ORDER",orderId,row.get("payable_amount"),payService.isDemoEnabled()?"DEMO":"WECHAT");
        int totalFen=new BigDecimal(row.get("payable_amount").toString()).movePointRight(2).intValueExact();
        if(payService.isDemoEnabled())
        {
            completeOrderPayment(paymentNo,"DEMO-"+nextNo("TX"),totalFen);
            Map<String,Object> result=new HashMap<>();result.put("demoPayment",true);result.put("paid",true);result.put("orderNo",orderNo);return result;
        }
        return payService.jsapi(paymentNo,String.valueOf(row.get("openid")),totalFen);
    }

    @Transactional
    public void completeOrderPayment(String paymentNo, String transactionId, Integer totalFen)
    {
        List<Map<String,Object>> rows=jdbcTemplate.queryForList("select payment_id,business_type,business_id,member_id,amount,status from xy_payment where payment_no=? for update",paymentNo);if(rows.isEmpty())throw new ServiceException("支付单不存在");Map<String,Object> payment=rows.get(0);if("SUCCESS".equals(payment.get("status")))return;if(!"PENDING".equals(payment.get("status")))throw new ServiceException("支付单状态异常");if(new BigDecimal(payment.get("amount").toString()).movePointRight(2).intValueExact()!=totalFen)throw new ServiceException("支付金额校验失败");Long businessId=((Number)payment.get("business_id")).longValue();jdbcTemplate.update("update xy_payment set status='SUCCESS',transaction_id=?,paid_time=now() where payment_no=?",transactionId,paymentNo);if("ORDER".equals(payment.get("business_type"))){jdbcTemplate.update("update xy_order set status='PAID',paid_amount=payable_amount,paid_time=now() where order_id=? and status='PENDING_PAYMENT'",businessId);}else if("MEMBERSHIP".equals(payment.get("business_type"))){activateMembership(businessId,paymentNo);}
    }

    @Transactional
    public Map<String,Object> createMembershipPayment(Long memberId, Long planId, XyWechatPayService payService){List<Map<String,Object>> plans=jdbcTemplate.queryForList("select plan_id,plan_name,amount from xy_membership_plan where plan_id=? and status='0'",planId);if(plans.isEmpty())throw new ServiceException("会员套餐不存在或已下架");Map<String,Object> plan=plans.get(0);String orderNo=nextNo("MO");jdbcTemplate.update("insert into xy_membership_order(order_no,member_id,plan_id,amount) values(?,?,?,?)",orderNo,memberId,planId,plan.get("amount"));Long id=jdbcTemplate.queryForObject("select last_insert_id()",Long.class);String paymentNo=nextNo("PY");jdbcTemplate.update("insert into xy_payment(payment_no,member_id,business_type,business_id,amount,channel) values(?,?,?,?,?,?)",paymentNo,memberId,"MEMBERSHIP",id,plan.get("amount"),payService.isDemoEnabled()?"DEMO":"WECHAT");int totalFen=new BigDecimal(plan.get("amount").toString()).movePointRight(2).intValueExact();if(payService.isDemoEnabled()){completeOrderPayment(paymentNo,"DEMO-"+nextNo("TX"),totalFen);Map<String,Object> result=new HashMap<>();result.put("demoPayment",true);result.put("paid",true);result.put("orderNo",orderNo);return result;}String openid=jdbcTemplate.queryForObject("select openid from xy_member where member_id=?",String.class,memberId);return payService.jsapi(paymentNo,openid,totalFen,"钓虾会员套餐");}

    private void activateMembership(Long membershipOrderId,String paymentNo){Map<String,Object> order=jdbcTemplate.queryForMap("select o.member_id,o.plan_id,p.duration_days from xy_membership_order o join xy_membership_plan p on p.plan_id=o.plan_id where o.membership_order_id=?",membershipOrderId);Long memberId=((Number)order.get("member_id")).longValue(),planId=((Number)order.get("plan_id")).longValue();int days=((Number)order.get("duration_days")).intValue();java.sql.Date max=jdbcTemplate.queryForObject("select max(expire_date) from xy_membership_card where member_id=? and status='ACTIVE'",java.sql.Date.class,memberId);LocalDate start=max!=null&&!max.toLocalDate().isBefore(LocalDate.now())?max.toLocalDate().plusDays(1):LocalDate.now();jdbcTemplate.update("insert into xy_membership_card(member_id,plan_id,card_no,start_date,expire_date,source_payment_no) values(?,?,?,?,?,?)",memberId,planId,nextNo("MC"),start,start.plusDays(days-1),paymentNo);jdbcTemplate.update("update xy_membership_order set status='PAID',paid_time=now() where membership_order_id=?",membershipOrderId);}

    public List<Map<String,Object>> memberBills(Long memberId){return jdbcTemplate.queryForList("select payment_no as paymentNo,business_type as businessType,amount,status,paid_time as paidTime,create_time as createTime from xy_payment where member_id=? order by create_time desc",memberId);}

    public Map<String,Object> issueMemberVerifyCode(Long memberId){if(currentCard(memberId)==null)throw new ServiceException("没有有效会员卡");String code;do{code=randomDigits(8);}while(redisCache.getCacheObject(MEMBER_VERIFY_PREFIX+code)!=null);redisCache.setCacheObject(MEMBER_VERIFY_PREFIX+code,memberId,2,TimeUnit.MINUTES);Map<String,Object> result=new HashMap<>();result.put("code",code);result.put("expiresIn",120);return result;}

    @Transactional
    public Map<String,Object> verifyMemberCode(String code,String operator){Long memberId=redisCache.getCacheObject(MEMBER_VERIFY_PREFIX+code);if(memberId==null)throw new ServiceException("会员码无效或已过期");Map<String,Object> member=memberProfile(memberId);jdbcTemplate.update("insert into xy_member_visit(member_id,verify_code,verified_by) values(?,?,?)",memberId,code,operator);redisCache.deleteObject(MEMBER_VERIFY_PREFIX+code);return member;}

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
        checkLength(reason, 255, "售后原因不能超过255个字符");
        checkLength(description, 1000, "售后说明不能超过1000个字符");
        List<Map<String, Object>> orders = jdbcTemplate.queryForList("select order_id,status from xy_order where member_id = ? and order_no = ? and status in ('PAID','SHIPPED','COMPLETED')", memberId, orderNo);
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
        String sql = "select m.member_id as memberId, m.nickname, m.mobile, m.invite_code as inviteCode, m.status, m.create_time as createTime, c.card_no as cardNo, c.expire_date as expireDate, c.status as cardStatus from xy_member m left join xy_membership_card c on c.card_id=(select c2.card_id from xy_membership_card c2 where c2.member_id=m.member_id order by c2.expire_date desc limit 1) where 1=1";
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
        Integer stock = number(input.get("stock")) == null ? 0 : number(input.get("stock")).intValue();
        if (salePrice.signum() <= 0 || stock < 0) throw new ServiceException("商品价格或库存不合法");
        int memberDiscountEnabled = Boolean.FALSE.equals(input.get("memberDiscountEnabled")) || "0".equals(String.valueOf(input.get("memberDiscountEnabled"))) ? 0 : 1;
        Long productId = number(input.get("productId"));
        String status = "1".equals(String.valueOf(input.get("status"))) ? "1" : "0";
        int sortOrder = number(input.get("sortOrder")) == null ? 0 : number(input.get("sortOrder")).intValue();
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
        result.put("activeMembers", jdbcTemplate.queryForObject("select count(distinct member_id) from xy_membership_card where status='ACTIVE' and expire_date>=curdate()", Integer.class));
        result.put("pendingOrders", jdbcTemplate.queryForObject("select count(1) from xy_order where status='PENDING_PAYMENT'", Integer.class));
        result.put("todayPaidAmount", jdbcTemplate.queryForObject("select coalesce(sum(paid_amount),0) from xy_order where date(paid_time)=curdate() and status in ('PAID','SHIPPED','COMPLETED')", BigDecimal.class));
        return result;
    }

    public List<Map<String, Object>> financeRecords()
    {
        return jdbcTemplate.queryForList("select p.payment_no as paymentNo, p.business_type as businessType, p.amount, p.channel, p.status, p.transaction_id as transactionId, p.paid_time as paidTime, p.create_time as createTime, m.nickname, m.mobile from xy_payment p join xy_member m on m.member_id=p.member_id order by p.create_time desc");
    }

    public List<Map<String,Object>> adminOrders(){return jdbcTemplate.queryForList("select o.order_id as orderId,o.order_no as orderNo,o.delivery_type as deliveryType,o.total_amount as totalAmount,o.discount_amount as discountAmount,o.member_discount_rate as memberDiscountRate,o.payable_amount as payableAmount,o.paid_amount as paidAmount,o.status,o.create_time as createTime,m.nickname,m.mobile from xy_order o join xy_member m on m.member_id=o.member_id order by o.create_time desc");}
    @Transactional public void shipOrder(String orderNo){int count=jdbcTemplate.update("update xy_order set status='SHIPPED',shipped_time=now() where order_no=? and status='PAID'",orderNo);if(count!=1)throw new ServiceException("当前订单不能发货");}
    public List<Map<String,Object>> adminAfterSales(){return jdbcTemplate.queryForList("select a.after_sale_no as afterSaleNo,a.reason,a.description_text as description,a.status,a.refund_no as refundNo,a.refund_id as refundId,a.create_time as createTime,o.order_no as orderNo,o.paid_amount as paidAmount,m.nickname,m.mobile from xy_after_sale a join xy_order o on o.order_id=a.order_id join xy_member m on m.member_id=a.member_id order by a.create_time desc");}
    public void rejectAfterSale(String afterSaleNo){int count=jdbcTemplate.update("update xy_after_sale set status='REJECTED' where after_sale_no=? and status='PENDING'",afterSaleNo);if(count!=1)throw new ServiceException("售后单当前不能拒绝");}
    @Transactional public void approveAfterSale(String afterSaleNo,XyWechatPayService payService){List<Map<String,Object>> rows=jdbcTemplate.queryForList("select a.order_id,o.paid_amount,p.transaction_id from xy_after_sale a join xy_order o on o.order_id=a.order_id join xy_payment p on p.business_type='ORDER' and p.business_id=o.order_id and p.status='SUCCESS' where a.after_sale_no=? and a.status in ('PENDING','REFUND_FAILED') for update",afterSaleNo);if(rows.isEmpty())throw new ServiceException("售后单或成功支付记录不存在");Map<String,Object> row=rows.get(0);int fen=new BigDecimal(row.get("paid_amount").toString()).movePointRight(2).intValueExact();String refundNo=nextNo("RF");Map<String,Object> refund=payService.refund(String.valueOf(row.get("transaction_id")),refundNo,fen,fen);jdbcTemplate.update("update xy_after_sale set status='REFUNDING',refund_no=?,refund_id=? where after_sale_no=?",refundNo,refund.get("refund_id"),afterSaleNo);jdbcTemplate.update("update xy_payment set status='REFUNDING' where business_type='ORDER' and business_id=?",row.get("order_id"));jdbcTemplate.update("update xy_order set status='AFTER_SALE' where order_id=?",row.get("order_id"));}
    @Transactional public void completeRefund(String refundNo,String refundId){List<Map<String,Object>> rows=jdbcTemplate.queryForList("select order_id from xy_after_sale where refund_no=? and status in('REFUNDING','APPROVED') for update",refundNo);if(rows.isEmpty())return;Long orderId=((Number)rows.get(0).get("order_id")).longValue();jdbcTemplate.update("update xy_after_sale set status='APPROVED',refund_id=? where refund_no=?",refundId,refundNo);jdbcTemplate.update("update xy_payment set status='REFUNDED' where business_type='ORDER' and business_id=?",orderId);jdbcTemplate.update("update xy_order set status='REFUNDED' where order_id=?",orderId);}
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
        Long storeId = number(input.get("storeId"));
        String status = "1".equals(String.valueOf(input.get("status"))) ? "1" : "0";
        if (storeId == null)
        {
            jdbcTemplate.update("insert into xy_store(store_name,address,phone,longitude,latitude,business_hours,status) values(?,?,?,?,?,?,?)", storeName, address, phone, input.get("longitude"), input.get("latitude"), businessHours, status);
            return jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
        }
        int count = jdbcTemplate.update("update xy_store set store_name=?,address=?,phone=?,longitude=?,latitude=?,business_hours=?,status=? where store_id=?", storeName, address, phone, input.get("longitude"), input.get("latitude"), businessHours, status, storeId);
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
        int sortOrder = number(input.get("sortOrder")) == null ? 0 : number(input.get("sortOrder")).intValue();
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
        int sortOrder = number(input.get("sortOrder")) == null ? 0 : number(input.get("sortOrder")).intValue();
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
        Integer durationDays = number(input.get("durationDays")) == null ? null : number(input.get("durationDays")).intValue();
        Integer dailyLimit = number(input.get("dailyReservationLimit")) == null ? 1 : number(input.get("dailyReservationLimit")).intValue();
        if (amount.signum() <= 0 || durationDays == null || durationDays < 1 || dailyLimit < 1) throw new ServiceException("套餐参数不合法");
        String status = "1".equals(String.valueOf(input.get("status"))) ? "1" : "0";
        int sortOrder = number(input.get("sortOrder")) == null ? 0 : number(input.get("sortOrder")).intValue();
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
}
