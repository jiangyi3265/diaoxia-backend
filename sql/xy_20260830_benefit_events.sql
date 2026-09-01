-- 福利钓专场：独立场次、22座选座、微信支付报名、后台资金处理与通知记录。
-- 本脚本可重复执行；普通预约的18个座位和会员资格规则保持不变。
-- 原普通预约 20:00-22:00 时段由福利钓专场替代，停用后历史预约仍完整保留。

UPDATE xy_reservation_slot
SET status = '1'
WHERE start_time = '20:00:00' AND end_time = '22:00:00' AND status = '0';

CREATE TABLE IF NOT EXISTS xy_benefit_event (
  event_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '福利钓场次ID',
  event_no VARCHAR(40) NOT NULL COMMENT '场次编号',
  store_id BIGINT NOT NULL COMMENT '门店ID',
  event_date DATE NOT NULL COMMENT '场次日期',
  start_time TIME NOT NULL DEFAULT '20:15:00' COMMENT '开始时间',
  end_time TIME NOT NULL DEFAULT '22:15:00' COMMENT '结束时间',
  signup_deadline TIME NOT NULL DEFAULT '19:30:00' COMMENT '报名截止时间',
  fee_amount DECIMAL(10,2) NOT NULL DEFAULT 100.00 COMMENT '报名费',
  announcement TEXT NOT NULL COMMENT '公告、奖品与开闭场条件',
  announcement_version INT NOT NULL DEFAULT 1 COMMENT '公告版本',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT/OPEN/CONFIRMED/CANCELED/FINISHED/DELETED',
  confirmed_time DATETIME DEFAULT NULL COMMENT '确认开始时间',
  canceled_time DATETIME DEFAULT NULL COMMENT '取消时间',
  cancel_reason VARCHAR(500) DEFAULT NULL COMMENT '取消原因',
  create_by VARCHAR(64) DEFAULT NULL COMMENT '创建人',
  update_by VARCHAR(64) DEFAULT NULL COMMENT '更新人',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (event_id),
  UNIQUE KEY uk_xy_benefit_event_no (event_no),
  UNIQUE KEY uk_xy_benefit_event_store_date (store_id, event_date),
  KEY idx_xy_benefit_event_date_status (event_date, status),
  CONSTRAINT fk_xy_benefit_event_store FOREIGN KEY (store_id) REFERENCES xy_store(store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='福利钓专场';

CREATE TABLE IF NOT EXISTS xy_benefit_booking (
  booking_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '报名ID',
  booking_no VARCHAR(40) NOT NULL COMMENT '报名编号',
  event_id BIGINT NOT NULL COMMENT '福利钓场次ID',
  member_id BIGINT NOT NULL COMMENT '报名用户ID',
  seat_no INT NOT NULL COMMENT '座位号1-22',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING_PAYMENT' COMMENT '状态：PENDING_PAYMENT/BOOKED/REFUNDING/CLOSED',
  seat_lock TINYINT DEFAULT 1 COMMENT '占座标记：1占用，NULL已释放',
  member_lock TINYINT DEFAULT 1 COMMENT '同场用户限制：1占用，NULL已释放',
  announcement_version INT NOT NULL COMMENT '确认的公告版本',
  announcement_snapshot TEXT NOT NULL COMMENT '确认时公告快照',
  announcement_confirmed_time DATETIME NOT NULL COMMENT '公告确认时间',
  start_notice_accepted TINYINT NOT NULL DEFAULT 0 COMMENT '是否接受开始通知',
  cancel_notice_accepted TINYINT NOT NULL DEFAULT 0 COMMENT '是否接受取消通知',
  payment_payload TEXT DEFAULT NULL COMMENT '5分钟内复用的小程序支付参数',
  expires_time DATETIME DEFAULT NULL COMMENT '待支付占座到期时间',
  booked_time DATETIME DEFAULT NULL COMMENT '支付成功报名时间',
  close_reason VARCHAR(500) DEFAULT NULL COMMENT '后台关闭原因',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (booking_id),
  UNIQUE KEY uk_xy_benefit_booking_no (booking_no),
  UNIQUE KEY uk_xy_benefit_booking_seat_lock (event_id, seat_no, seat_lock),
  UNIQUE KEY uk_xy_benefit_booking_member_lock (event_id, member_id, member_lock),
  KEY idx_xy_benefit_booking_member (member_id, create_time),
  KEY idx_xy_benefit_booking_event_status (event_id, status),
  CONSTRAINT fk_xy_benefit_booking_event FOREIGN KEY (event_id) REFERENCES xy_benefit_event(event_id),
  CONSTRAINT fk_xy_benefit_booking_member FOREIGN KEY (member_id) REFERENCES xy_member(member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='福利钓报名';

CREATE TABLE IF NOT EXISTS xy_benefit_refund (
  benefit_refund_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '福利钓资金处理ID',
  booking_id BIGINT NOT NULL COMMENT '报名ID',
  refund_no VARCHAR(64) NOT NULL COMMENT '商户退款单号',
  refund_id VARCHAR(64) DEFAULT NULL COMMENT '微信退款单号',
  amount DECIMAL(10,2) NOT NULL COMMENT '处理金额',
  reason VARCHAR(500) NOT NULL COMMENT '后台处理原因',
  status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING' COMMENT '状态：PROCESSING/SUCCESS/FAILED',
  create_by VARCHAR(64) DEFAULT NULL COMMENT '操作人',
  complete_time DATETIME DEFAULT NULL COMMENT '完成时间',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (benefit_refund_id),
  UNIQUE KEY uk_xy_benefit_refund_booking (booking_id),
  UNIQUE KEY uk_xy_benefit_refund_no (refund_no),
  KEY idx_xy_benefit_refund_status (status, create_time),
  CONSTRAINT fk_xy_benefit_refund_booking FOREIGN KEY (booking_id) REFERENCES xy_benefit_booking(booking_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='福利钓后台资金处理';

CREATE TABLE IF NOT EXISTS xy_benefit_notification_record (
  notification_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '通知记录ID',
  booking_id BIGINT NOT NULL COMMENT '报名ID',
  notice_type VARCHAR(16) NOT NULL COMMENT '通知类型：START/CANCEL',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/SENT/FAILED/SKIPPED',
  error_message VARCHAR(500) DEFAULT NULL COMMENT '失败原因',
  sent_time DATETIME DEFAULT NULL COMMENT '发送时间',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (notification_id),
  UNIQUE KEY uk_xy_benefit_notification (booking_id, notice_type),
  KEY idx_xy_benefit_notification_status (status, create_time),
  CONSTRAINT fk_xy_benefit_notification_booking FOREIGN KEY (booking_id) REFERENCES xy_benefit_booking(booking_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='福利钓订阅通知记录';

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, `query`, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 10009, '福利钓专场', 10000, 5, 'benefit-events', 'xiayu/benefit-events', '', 'XyBenefitEvents', 1, 0, 'C', '0', '0', 'xy:benefit:list', 'star', 'admin', NOW(), '福利钓场次、选座与报名管理'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id=10009);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, `query`, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT menu_id, menu_name, 10009, order_num, '', '', '', '', 1, 0, 'F', '0', '0', perms, '#', 'admin', NOW(), remark
FROM (
  SELECT 10016 menu_id, '福利钓维护' menu_name, 1 order_num, 'xy:benefit:edit' perms, '创建、编辑、确认及取消福利钓场次' remark
  UNION ALL SELECT 10017, '福利钓资金处理', 2, 'xy:benefit:refund', '福利钓单座与整场资金处理'
) permissions
WHERE NOT EXISTS (SELECT 1 FROM sys_menu existing_menu WHERE existing_menu.menu_id=permissions.menu_id);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, menu_id FROM sys_menu
WHERE menu_id IN (10009,10016,10017)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id=1 AND sys_role_menu.menu_id=sys_menu.menu_id);
