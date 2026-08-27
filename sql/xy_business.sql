-- 钓虾业务库初始化（MySQL 8.0+，utf8mb4）
-- 本脚本只创建 xy_* 业务表，不修改若依 sys_* 系统表。

CREATE TABLE IF NOT EXISTS xy_store (
  store_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '门店ID',
  store_name VARCHAR(100) NOT NULL COMMENT '门店名称',
  address VARCHAR(255) NOT NULL COMMENT '地址',
  phone VARCHAR(32) NOT NULL COMMENT '联系电话',
  longitude DECIMAL(10,7) DEFAULT NULL COMMENT '经度',
  latitude DECIMAL(10,7) DEFAULT NULL COMMENT '纬度',
  business_hours VARCHAR(100) NOT NULL COMMENT '营业时间说明',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '状态：0营业，1停业',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钓虾门店';

CREATE TABLE IF NOT EXISTS xy_member (
  member_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '会员ID',
  openid VARCHAR(64) NOT NULL COMMENT '微信OpenID',
  unionid VARCHAR(64) DEFAULT NULL COMMENT '微信UnionID',
  nickname VARCHAR(100) DEFAULT NULL COMMENT '昵称',
  avatar_url VARCHAR(500) DEFAULT NULL COMMENT '头像地址',
  mobile VARCHAR(32) DEFAULT NULL COMMENT '手机号',
  invite_code VARCHAR(12) NOT NULL COMMENT '邀请码',
  inviter_member_id BIGINT DEFAULT NULL COMMENT '邀请人会员ID（首次绑定后不可改）',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '状态：0正常，1停用',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (member_id),
  UNIQUE KEY uk_xy_member_openid (openid),
  UNIQUE KEY uk_xy_member_invite_code (invite_code),
  KEY idx_xy_member_inviter (inviter_member_id),
  CONSTRAINT fk_xy_member_inviter FOREIGN KEY (inviter_member_id) REFERENCES xy_member(member_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钓虾会员';

-- 兼容已建库：补充邀请归属字段、索引和自关联外键。
SET @xy_inviter_column = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE xy_member ADD COLUMN inviter_member_id BIGINT DEFAULT NULL COMMENT ''邀请人会员ID（首次绑定后不可改）'' AFTER invite_code',
  'SELECT 1') FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='xy_member' AND column_name='inviter_member_id');
PREPARE xy_inviter_column_stmt FROM @xy_inviter_column;
EXECUTE xy_inviter_column_stmt;
DEALLOCATE PREPARE xy_inviter_column_stmt;

SET @xy_inviter_index = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE xy_member ADD KEY idx_xy_member_inviter (inviter_member_id)',
  'SELECT 1') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='xy_member' AND index_name='idx_xy_member_inviter');
PREPARE xy_inviter_index_stmt FROM @xy_inviter_index;
EXECUTE xy_inviter_index_stmt;
DEALLOCATE PREPARE xy_inviter_index_stmt;

SET @xy_inviter_fk = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE xy_member ADD CONSTRAINT fk_xy_member_inviter FOREIGN KEY (inviter_member_id) REFERENCES xy_member(member_id) ON DELETE SET NULL',
  'SELECT 1') FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='xy_member' AND constraint_name='fk_xy_member_inviter');
PREPARE xy_inviter_fk_stmt FROM @xy_inviter_fk;
EXECUTE xy_inviter_fk_stmt;
DEALLOCATE PREPARE xy_inviter_fk_stmt;

CREATE TABLE IF NOT EXISTS xy_membership_plan (
  plan_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '套餐ID',
  plan_name VARCHAR(100) NOT NULL COMMENT '套餐名称',
  amount DECIMAL(10,2) NOT NULL COMMENT '金额',
  duration_days INT NOT NULL COMMENT '有效天数',
  daily_reservation_limit INT NOT NULL DEFAULT 1 COMMENT '每日预约次数',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '状态：0上架，1下架',
  sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员套餐';

CREATE TABLE IF NOT EXISTS xy_membership_card (
  card_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '会员卡ID',
  member_id BIGINT NOT NULL COMMENT '会员ID',
  plan_id BIGINT NOT NULL COMMENT '套餐ID',
  card_no VARCHAR(32) NOT NULL COMMENT '卡号',
  start_date DATE NOT NULL COMMENT '生效日期',
  expire_date DATE NOT NULL COMMENT '到期日期',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/EXPIRED/REFUNDED',
  usage_count INT NOT NULL DEFAULT 0 COMMENT '已使用次数',
  source_payment_no VARCHAR(40) DEFAULT NULL COMMENT '来源支付单号',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (card_id),
  UNIQUE KEY uk_xy_card_no (card_no),
  KEY idx_xy_card_member_status (member_id, status, expire_date),
  CONSTRAINT fk_xy_card_member FOREIGN KEY (member_id) REFERENCES xy_member(member_id),
  CONSTRAINT fk_xy_card_plan FOREIGN KEY (plan_id) REFERENCES xy_membership_plan(plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员卡';

CREATE TABLE IF NOT EXISTS xy_membership_order (
  membership_order_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '开卡订单ID',
  order_no VARCHAR(40) NOT NULL COMMENT '开卡订单号',
  member_id BIGINT NOT NULL COMMENT '会员ID',
  plan_id BIGINT NOT NULL COMMENT '套餐ID',
  amount DECIMAL(10,2) NOT NULL COMMENT '应付金额快照',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING_PAYMENT' COMMENT '状态',
  paid_time DATETIME DEFAULT NULL COMMENT '支付时间',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (membership_order_id),
  UNIQUE KEY uk_xy_membership_order_no (order_no),
  KEY idx_xy_membership_order_member (member_id, status, create_time),
  CONSTRAINT fk_xy_membership_order_member FOREIGN KEY (member_id) REFERENCES xy_member(member_id),
  CONSTRAINT fk_xy_membership_order_plan FOREIGN KEY (plan_id) REFERENCES xy_membership_plan(plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员开卡订单';

CREATE TABLE IF NOT EXISTS xy_member_visit (
  visit_id BIGINT NOT NULL AUTO_INCREMENT,
  member_id BIGINT NOT NULL,
  verify_code VARCHAR(16) NOT NULL,
  verified_by VARCHAR(64) DEFAULT NULL,
  checkin_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (visit_id),
  KEY idx_xy_member_visit_code (verify_code),
  KEY idx_xy_member_visit_member (member_id, checkin_time),
  CONSTRAINT fk_xy_member_visit_member FOREIGN KEY (member_id) REFERENCES xy_member(member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员到店核销记录';

-- 4位动态会员码每10秒轮换，历史码允许重复，不能再使用全表唯一索引。
SET @xy_member_visit_unique = (SELECT IF(COUNT(*)>0,
  'ALTER TABLE xy_member_visit DROP INDEX uk_xy_member_visit_code',
  'SELECT 1') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='xy_member_visit' AND index_name='uk_xy_member_visit_code');
PREPARE xy_member_visit_unique_stmt FROM @xy_member_visit_unique;
EXECUTE xy_member_visit_unique_stmt;
DEALLOCATE PREPARE xy_member_visit_unique_stmt;

SET @xy_member_visit_index = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE xy_member_visit ADD KEY idx_xy_member_visit_code (verify_code)',
  'SELECT 1') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='xy_member_visit' AND index_name='idx_xy_member_visit_code');
PREPARE xy_member_visit_index_stmt FROM @xy_member_visit_index;
EXECUTE xy_member_visit_index_stmt;
DEALLOCATE PREPARE xy_member_visit_index_stmt;

CREATE TABLE IF NOT EXISTS xy_reservation_slot (
  slot_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '时段ID',
  store_id BIGINT NOT NULL COMMENT '门店ID',
  start_time TIME NOT NULL COMMENT '开始时间',
  end_time TIME NOT NULL COMMENT '结束时间',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '状态：0启用，1停用',
  sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (slot_id),
  UNIQUE KEY uk_xy_slot_time (store_id, start_time, end_time),
  CONSTRAINT fk_xy_slot_store FOREIGN KEY (store_id) REFERENCES xy_store(store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约时段';

CREATE TABLE IF NOT EXISTS xy_seat (
  seat_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '座位ID',
  store_id BIGINT NOT NULL COMMENT '门店ID',
  seat_code VARCHAR(32) NOT NULL COMMENT '座位编号',
  zone_name VARCHAR(32) DEFAULT NULL COMMENT '区域名称',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '状态：0可用，1停用',
  sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (seat_id),
  UNIQUE KEY uk_xy_seat_code (store_id, seat_code),
  CONSTRAINT fk_xy_seat_store FOREIGN KEY (store_id) REFERENCES xy_store(store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钓位';

CREATE TABLE IF NOT EXISTS xy_reservation (
  reservation_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '预约ID',
  reservation_no VARCHAR(40) NOT NULL COMMENT '预约单号',
  member_id BIGINT NOT NULL COMMENT '会员ID',
  store_id BIGINT NOT NULL COMMENT '门店ID',
  slot_id BIGINT NOT NULL COMMENT '时段ID',
  seat_id BIGINT NOT NULL COMMENT '座位ID',
  reservation_date DATE NOT NULL COMMENT '预约日期',
  status VARCHAR(16) NOT NULL DEFAULT 'BOOKED' COMMENT '状态：BOOKED/CHECKED_IN/CANCELED/NO_SHOW',
  seat_lock TINYINT DEFAULT 1 COMMENT '占座标记：1占用，NULL已释放（NULL允许保留多条历史记录）',
  checkin_time DATETIME DEFAULT NULL COMMENT '签到时间',
  cancel_time DATETIME DEFAULT NULL COMMENT '取消时间',
  verify_code VARCHAR(32) NOT NULL COMMENT '核销码',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (reservation_id),
  UNIQUE KEY uk_xy_reservation_no (reservation_no),
  UNIQUE KEY uk_xy_reservation_verify (verify_code),
  UNIQUE KEY uk_xy_reservation_seat_lock (reservation_date, slot_id, seat_id, seat_lock),
  KEY idx_xy_reservation_member_date (member_id, reservation_date),
  KEY idx_xy_reservation_store_date (store_id, reservation_date, slot_id),
  CONSTRAINT fk_xy_reservation_member FOREIGN KEY (member_id) REFERENCES xy_member(member_id),
  CONSTRAINT fk_xy_reservation_store FOREIGN KEY (store_id) REFERENCES xy_store(store_id),
  CONSTRAINT fk_xy_reservation_slot FOREIGN KEY (slot_id) REFERENCES xy_reservation_slot(slot_id),
  CONSTRAINT fk_xy_reservation_seat FOREIGN KEY (seat_id) REFERENCES xy_seat(seat_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钓位预约';

-- 兼容早期版本：已释放记录改用 NULL，唯一索引只约束仍在占用的座位。
SET @xy_lock_nullable = (SELECT IF(is_nullable='NO',
  'ALTER TABLE xy_reservation DROP INDEX uk_xy_reservation_seat_lock, MODIFY seat_lock TINYINT NULL DEFAULT 1 COMMENT ''占座标记：1占用，NULL已释放'', ADD UNIQUE KEY uk_xy_reservation_seat_lock (reservation_date, slot_id, seat_id, seat_lock)',
  'SELECT 1') FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='xy_reservation' AND column_name='seat_lock');
PREPARE xy_lock_stmt FROM @xy_lock_nullable;
EXECUTE xy_lock_stmt;
DEALLOCATE PREPARE xy_lock_stmt;
UPDATE xy_reservation SET seat_lock = NULL WHERE seat_lock = 0;

CREATE TABLE IF NOT EXISTS xy_product (
  product_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '商品ID',
  product_name VARCHAR(150) NOT NULL COMMENT '商品名称',
  category_name VARCHAR(64) NOT NULL COMMENT '分类',
  cover_url VARCHAR(500) DEFAULT NULL COMMENT '主图',
  detail_text TEXT DEFAULT NULL COMMENT '详情',
  sale_price DECIMAL(10,2) NOT NULL COMMENT '销售价',
  member_discount_enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否参与会员折扣：1参与，0不参与',
  stock INT NOT NULL DEFAULT 0 COMMENT '库存',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '状态：0上架，1下架',
  sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (product_id),
  KEY idx_xy_product_status_sort (status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城商品';

CREATE TABLE IF NOT EXISTS xy_address (
  address_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '地址ID',
  member_id BIGINT NOT NULL COMMENT '会员ID',
  receiver_name VARCHAR(64) NOT NULL COMMENT '收货人',
  receiver_mobile VARCHAR(32) NOT NULL COMMENT '收货电话',
  province VARCHAR(64) NOT NULL COMMENT '省',
  city VARCHAR(64) NOT NULL COMMENT '市',
  district VARCHAR(64) NOT NULL COMMENT '区',
  detail VARCHAR(255) NOT NULL COMMENT '详细地址',
  is_default TINYINT NOT NULL DEFAULT 0 COMMENT '是否默认地址',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (address_id),
  KEY idx_xy_address_member (member_id, is_default),
  CONSTRAINT fk_xy_address_member FOREIGN KEY (member_id) REFERENCES xy_member(member_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员收货地址';

CREATE TABLE IF NOT EXISTS xy_order (
  order_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '订单ID',
  order_no VARCHAR(40) NOT NULL COMMENT '订单号',
  member_id BIGINT NOT NULL COMMENT '会员ID',
  address_id BIGINT DEFAULT NULL COMMENT '地址ID',
  delivery_type VARCHAR(16) NOT NULL COMMENT '配送类型：DELIVERY/PICKUP',
  total_amount DECIMAL(10,2) NOT NULL COMMENT '商品总额',
  discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '会员折扣金额',
  member_discount_rate DECIMAL(5,4) NOT NULL DEFAULT 1.0000 COMMENT '会员折扣率快照',
  freight_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '运费',
  payable_amount DECIMAL(10,2) NOT NULL COMMENT '应付金额',
  paid_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '实付金额',
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING_PAYMENT' COMMENT '状态',
  receiver_snapshot VARCHAR(600) DEFAULT NULL COMMENT '收货信息快照',
  paid_time DATETIME DEFAULT NULL COMMENT '支付时间',
  shipped_time DATETIME DEFAULT NULL COMMENT '发货时间',
  received_time DATETIME DEFAULT NULL COMMENT '确认收货时间',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (order_id),
  UNIQUE KEY uk_xy_order_no (order_no),
  KEY idx_xy_order_member_status (member_id, status, create_time),
  CONSTRAINT fk_xy_order_member FOREIGN KEY (member_id) REFERENCES xy_member(member_id),
  CONSTRAINT fk_xy_order_address FOREIGN KEY (address_id) REFERENCES xy_address(address_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城订单';

CREATE TABLE IF NOT EXISTS xy_order_item (
  item_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '明细ID',
  order_id BIGINT NOT NULL COMMENT '订单ID',
  product_id BIGINT NOT NULL COMMENT '商品ID',
  product_name VARCHAR(150) NOT NULL COMMENT '商品名称快照',
  cover_url VARCHAR(500) DEFAULT NULL COMMENT '商品主图快照',
  sale_price DECIMAL(10,2) NOT NULL COMMENT '成交单价',
  quantity INT NOT NULL COMMENT '数量',
  subtotal_amount DECIMAL(10,2) NOT NULL COMMENT '小计',
  PRIMARY KEY (item_id),
  KEY idx_xy_order_item_order (order_id),
  CONSTRAINT fk_xy_order_item_order FOREIGN KEY (order_id) REFERENCES xy_order(order_id) ON DELETE CASCADE,
  CONSTRAINT fk_xy_order_item_product FOREIGN KEY (product_id) REFERENCES xy_product(product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商城订单明细';

CREATE TABLE IF NOT EXISTS xy_after_sale (
  after_sale_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '售后ID',
  after_sale_no VARCHAR(40) NOT NULL COMMENT '售后单号',
  order_id BIGINT NOT NULL COMMENT '订单ID',
  member_id BIGINT NOT NULL COMMENT '会员ID',
  reason VARCHAR(255) NOT NULL COMMENT '原因',
  description_text VARCHAR(1000) DEFAULT NULL COMMENT '说明',
  original_order_status VARCHAR(24) DEFAULT NULL COMMENT '申请售后前订单状态',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/REFUNDING/APPROVED/RESTOCKED/REJECTED/REFUND_FAILED',
  refund_no VARCHAR(64) DEFAULT NULL COMMENT '商户退款单号',
  refund_id VARCHAR(64) DEFAULT NULL COMMENT '微信退款单号',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (after_sale_id),
  UNIQUE KEY uk_xy_after_sale_no (after_sale_no),
  UNIQUE KEY uk_xy_after_sale_refund_no (refund_no),
  KEY idx_xy_after_sale_order (order_id),
  CONSTRAINT fk_xy_after_sale_order FOREIGN KEY (order_id) REFERENCES xy_order(order_id),
  CONSTRAINT fk_xy_after_sale_member FOREIGN KEY (member_id) REFERENCES xy_member(member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单售后';

SET @xy_refund_column = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE xy_after_sale ADD COLUMN refund_id VARCHAR(64) DEFAULT NULL COMMENT ''微信退款单号'' AFTER status',
  'SELECT 1') FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='xy_after_sale' AND column_name='refund_id');
PREPARE xy_refund_stmt FROM @xy_refund_column;
EXECUTE xy_refund_stmt;
DEALLOCATE PREPARE xy_refund_stmt;

SET @xy_refund_no_column = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE xy_after_sale ADD COLUMN refund_no VARCHAR(64) DEFAULT NULL COMMENT ''商户退款单号'' AFTER status, ADD UNIQUE KEY uk_xy_after_sale_refund_no (refund_no)',
  'SELECT 1') FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='xy_after_sale' AND column_name='refund_no');
PREPARE xy_refund_no_stmt FROM @xy_refund_no_column;
EXECUTE xy_refund_no_stmt;
DEALLOCATE PREPARE xy_refund_no_stmt;

SET @xy_original_status_column = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE xy_after_sale ADD COLUMN original_order_status VARCHAR(24) DEFAULT NULL COMMENT ''申请售后前订单状态'' AFTER description_text',
  'SELECT 1') FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='xy_after_sale' AND column_name='original_order_status');
PREPARE xy_original_status_stmt FROM @xy_original_status_column;
EXECUTE xy_original_status_stmt;
DEALLOCATE PREPARE xy_original_status_stmt;

CREATE TABLE IF NOT EXISTS xy_payment (
  payment_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '支付ID',
  payment_no VARCHAR(40) NOT NULL COMMENT '支付单号',
  member_id BIGINT NOT NULL COMMENT '会员ID',
  business_type VARCHAR(24) NOT NULL COMMENT '业务类型：MEMBERSHIP/ORDER',
  business_id BIGINT NOT NULL COMMENT '业务主键',
  amount DECIMAL(10,2) NOT NULL COMMENT '支付金额',
  channel VARCHAR(24) NOT NULL COMMENT '支付渠道：WECHAT/OFFLINE/DEMO',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/SUCCESS/CLOSED/REFUNDING/REFUNDED',
  transaction_id VARCHAR(64) DEFAULT NULL COMMENT '渠道交易号',
  paid_time DATETIME DEFAULT NULL COMMENT '支付时间',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (payment_id),
  UNIQUE KEY uk_xy_payment_no (payment_no),
  UNIQUE KEY uk_xy_payment_business (business_type, business_id),
  KEY idx_xy_payment_member_status (member_id, status),
  CONSTRAINT fk_xy_payment_member FOREIGN KEY (member_id) REFERENCES xy_member(member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付流水';

CREATE TABLE IF NOT EXISTS xy_business_setting (
  setting_key VARCHAR(64) NOT NULL COMMENT '设置键',
  setting_value VARCHAR(255) NOT NULL COMMENT '设置值',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钓虾业务设置';

INSERT INTO xy_business_setting(setting_key, setting_value)
VALUES ('member_product_discount_rate', '0.95')
ON DUPLICATE KEY UPDATE setting_value = setting_value;

CREATE TABLE IF NOT EXISTS xy_reservation_notification_record (
  notification_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '通知记录ID',
  reservation_id BIGINT NOT NULL COMMENT '预约ID',
  reminder_type VARCHAR(24) NOT NULL COMMENT '提醒类型：DAY_BEFORE/TWO_HOURS',
  scheduled_for DATETIME NOT NULL COMMENT '计划发送时间',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/SENT/FAILED',
  error_message VARCHAR(500) DEFAULT NULL COMMENT '失败原因',
  sent_time DATETIME DEFAULT NULL COMMENT '实际发送时间',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (notification_id),
  UNIQUE KEY uk_xy_reservation_notification (reservation_id, reminder_type),
  KEY idx_xy_reservation_notification_status (status, scheduled_for),
  CONSTRAINT fk_xy_reservation_notification_reservation FOREIGN KEY (reservation_id) REFERENCES xy_reservation(reservation_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约订阅消息发送记录';

-- 后台菜单：通过若依动态路由加载，禁止把运营页面放在前端匿名白名单中。
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, `query`, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 10000, '钓虾运营', 0, 20, 'xiayu', 'xiayu/layout', '', 'XyAdmin', 1, 0, 'M', '0', '0', '', 'dashboard', 'admin', NOW(), '钓虾运营后台'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 10000);

UPDATE sys_menu
SET menu_name = '钓虾运营', path = 'xiayu', component = 'xiayu/layout', status = '0', visible = '0'
WHERE menu_id = 10000;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, `query`, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT menu_id, menu_name, 10000, order_num, path, component, '', route_name, 1, 0, 'C', '0', '0', perms, icon, 'admin', NOW(), '钓虾运营功能'
FROM (
  SELECT 10001 menu_id, '数据看板' menu_name, 1 order_num, 'dashboard' path, 'xiayu/dashboard' component, 'XyDashboard' route_name, 'xy:dashboard:view' perms, 'dashboard' icon
  UNION ALL SELECT 10002, '会员管理', 2, 'members', 'xiayu/members', 'XyMembers', 'xy:member:list', 'user'
  UNION ALL SELECT 10003, '预约管理', 3, 'reservations', 'xiayu/reservations', 'XyReservations', 'xy:reservation:list', 'calendar'
  UNION ALL SELECT 10004, '座位时段', 4, 'seats', 'xiayu/seats', 'XySeats', 'xy:reservation:config', 'tree'
  UNION ALL SELECT 10005, '商品订单', 5, 'mall', 'xiayu/mall', 'XyMall', 'xy:product:list', 'shopping'
  UNION ALL SELECT 10006, '核销管理', 6, 'verify', 'xiayu/verify', 'XyVerify', 'xy:reservation:verify', 'validCode'
  UNION ALL SELECT 10007, '财务对账', 7, 'finance', 'xiayu/finance', 'XyFinance', 'xy:finance:view', 'money'
  UNION ALL SELECT 10008, '员工权限', 8, 'staff', 'xiayu/staff', 'XyStaff', 'xy:staff:list', 'people'
) AS menus
WHERE NOT EXISTS (SELECT 1 FROM sys_menu existing_menu WHERE existing_menu.menu_id = menus.menu_id);

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, `query`, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT menu_id, menu_name, parent_id, 1, '', '', '', '', 1, 0, 'F', '0', '0', perms, '#', 'admin', NOW(), '钓虾运营操作权限'
FROM (
  SELECT 10011 menu_id, '商品编辑' menu_name, 10005 parent_id, 'xy:product:edit' perms
  UNION ALL SELECT 10012, '预约核销', 10006, 'xy:reservation:verify'
  UNION ALL SELECT 10013, '会员套餐维护', 10002, 'xy:member:plan'
  UNION ALL SELECT 10014, '线下收退款', 10007, 'xy:finance:collect'
  UNION ALL SELECT 10015, '会员资料维护', 10002, 'xy:member:edit'
) AS permissions
WHERE NOT EXISTS (SELECT 1 FROM sys_menu existing_menu WHERE existing_menu.menu_id = permissions.menu_id);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, menu.menu_id FROM sys_menu menu
WHERE menu.menu_id BETWEEN 10000 AND 10015
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu role_menu WHERE role_menu.role_id = 1 AND role_menu.menu_id = menu.menu_id);
