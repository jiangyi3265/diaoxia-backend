package com.ruoyi.web.service.xy;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 按预约开始时间发送前一天和前两小时的微信订阅消息，并用唯一记录避免重复发送。 */
@Service
public class XyReservationReminderService
{
    private final JdbcTemplate jdbcTemplate;
    private final XyWechatService wechatService;

    @Value("${xy.reservation-reminder-lookback-minutes:10}")
    private int reminderLookbackMinutes;

    public XyReservationReminderService(JdbcTemplate jdbcTemplate, XyWechatService wechatService)
    {
        this.jdbcTemplate = jdbcTemplate;
        this.wechatService = wechatService;
    }

    @Scheduled(cron = "${xy.reservation-reminder-cron:0 * * * * ?}")
    public void sendDueReservationReminders()
    {
        if (!wechatService.isReservationReminderConfigured())
        {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> reservations = jdbcTemplate.queryForList(
                "select r.reservation_id as reservationId, m.openid, st.store_name as storeName, se.seat_code as seatCode, "
                        + "timestamp(r.reservation_date, s.start_time) as reservationTime "
                        + "from xy_reservation r "
                        + "join xy_member m on m.member_id=r.member_id "
                        + "join xy_reservation_slot s on s.slot_id=r.slot_id "
                        + "join xy_store st on st.store_id=r.store_id "
                        + "join xy_seat se on se.seat_id=r.seat_id "
                        + "where r.status in ('BOOKED','CHECKED_IN') "
                        + "and r.reservation_date between curdate() and date_add(curdate(), interval 1 day)");
        for (Map<String, Object> reservation : reservations)
        {
            Timestamp timestamp = (Timestamp) reservation.get("reservationTime");
            if (timestamp == null) continue;
            LocalDateTime reservationTime = timestamp.toLocalDateTime();
            attemptReminder(reservation, "DAY_BEFORE", reservationTime.minusDays(1), reservationTime, now);
            attemptReminder(reservation, "TWO_HOURS", reservationTime.minusHours(2), reservationTime, now);
        }
    }

    private void attemptReminder(Map<String, Object> reservation, String reminderType, LocalDateTime scheduledFor,
            LocalDateTime reservationTime, LocalDateTime now)
    {
        int lookback = Math.max(1, Math.min(reminderLookbackMinutes, 60));
        if (scheduledFor.isAfter(now) || scheduledFor.plusMinutes(lookback).isBefore(now))
        {
            return;
        }
        Long reservationId = ((Number) reservation.get("reservationId")).longValue();
        boolean shouldSend = false;
        try
        {
            int inserted = jdbcTemplate.update("insert into xy_reservation_notification_record(reservation_id,reminder_type,scheduled_for,status) values(?,?,?,'PENDING')",
                    reservationId, reminderType, Timestamp.valueOf(scheduledFor));
            shouldSend = inserted == 1;
        }
        catch (DuplicateKeyException ex)
        {
            Integer retry = jdbcTemplate.update(
                    "update xy_reservation_notification_record set status='PENDING',error_message=null where reservation_id=? and reminder_type=? and status='FAILED'",
                    reservationId, reminderType);
            shouldSend = retry != null && retry == 1;
        }
        if (!shouldSend) return;
        String error = wechatService.sendReservationReminder(String.valueOf(reservation.get("openid")),
                String.valueOf(reservation.get("storeName")), String.valueOf(reservation.get("seatCode")), reservationTime);
        if (error == null)
        {
            jdbcTemplate.update("update xy_reservation_notification_record set status='SENT',sent_time=now() where reservation_id=? and reminder_type=?",
                    reservationId, reminderType);
        }
        else
        {
            jdbcTemplate.update("update xy_reservation_notification_record set status='FAILED',error_message=? where reservation_id=? and reminder_type=?",
                    error, reservationId, reminderType);
        }
    }
}
