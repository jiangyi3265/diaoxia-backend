-- 2026-08-22：成泰钓虾俱乐部上线业务数据。
--
-- 内容：正式门店、6 个可预约时段、18 个钓位、包月会员价 88 元、下架 7 个演示商品。
-- 全部幂等，可重复执行。执行前请先备份数据库。
--
-- 执行后请核对文件末尾的核对查询：时段应为 6、钓位应为 18、会员价应为 88.00。

SET NAMES utf8mb4;

-- ---------------------------------------------------------------
-- 1. 门店
-- ---------------------------------------------------------------
INSERT INTO xy_store(store_name, address, phone, business_hours, status)
SELECT '成泰钓虾俱乐部', '江门市蓬江区江侨路庙子里21号', '17266982318', '10:00-22:00', '0'
WHERE NOT EXISTS (SELECT 1 FROM xy_store WHERE store_name = '成泰钓虾俱乐部');

-- 已存在时同步为最新的门店资料，并确保处于营业状态。
UPDATE xy_store
SET address = '江门市蓬江区江侨路庙子里21号',
    phone = '17266982318',
    business_hours = '10:00-22:00',
    status = '0'
WHERE store_name = '成泰钓虾俱乐部';

SET @store_id = (SELECT store_id FROM xy_store WHERE store_name = '成泰钓虾俱乐部' ORDER BY store_id LIMIT 1);

-- @store_id 为空说明门店没建成，后面的时段和钓位都会插不进去，此时请停止并检查。
SELECT @store_id AS `门店ID（不能为空）`;

-- ---------------------------------------------------------------
-- 2. 可预约时段：10:00 起每 2 小时一场，最后一场 20:00-22:00
--    uk_xy_slot_time(store_id,start_time,end_time) 保证重复执行不会插入重复时段
-- ---------------------------------------------------------------
INSERT IGNORE INTO xy_reservation_slot(store_id, start_time, end_time, status, sort_order) VALUES
  (@store_id, '10:00:00', '12:00:00', '0', 10),
  (@store_id, '12:00:00', '14:00:00', '0', 20),
  (@store_id, '14:00:00', '16:00:00', '0', 30),
  (@store_id, '16:00:00', '18:00:00', '0', 40),
  (@store_id, '18:00:00', '20:00:00', '0', 50),
  (@store_id, '20:00:00', '22:00:00', '0', 60);

-- ---------------------------------------------------------------
-- 3. 钓位：池两侧 1-18，共 18 个
--    uk_xy_seat_code(store_id,seat_code) 保证重复执行不会插入重复钓位
-- ---------------------------------------------------------------
INSERT IGNORE INTO xy_seat(store_id, seat_code, zone_name, status, sort_order) VALUES
  (@store_id, '1',  NULL, '0', 1),
  (@store_id, '2',  NULL, '0', 2),
  (@store_id, '3',  NULL, '0', 3),
  (@store_id, '4',  NULL, '0', 4),
  (@store_id, '5',  NULL, '0', 5),
  (@store_id, '6',  NULL, '0', 6),
  (@store_id, '7',  NULL, '0', 7),
  (@store_id, '8',  NULL, '0', 8),
  (@store_id, '9',  NULL, '0', 9),
  (@store_id, '10', NULL, '0', 10),
  (@store_id, '11', NULL, '0', 11),
  (@store_id, '12', NULL, '0', 12),
  (@store_id, '13', NULL, '0', 13),
  (@store_id, '14', NULL, '0', 14),
  (@store_id, '15', NULL, '0', 15),
  (@store_id, '16', NULL, '0', 16),
  (@store_id, '17', NULL, '0', 17),
  (@store_id, '18', NULL, '0', 18);

-- ---------------------------------------------------------------
-- 4. 包月会员价：99 元改为 88 元
-- ---------------------------------------------------------------
UPDATE xy_membership_plan
SET amount = 88.00
WHERE duration_days = 30 AND status = '0';

-- ---------------------------------------------------------------
-- 5. 下架演示商品（只改状态，后台随时可重新上架）
-- ---------------------------------------------------------------
UPDATE xy_product
SET status = '1'
WHERE product_name IN (
  '轻量钓虾竿', '鲜虾海鲜意面', '鲜香虾丸饵料', '招牌秘制蘸料',
  '香酥薯条', '冰爽啤酒', '海鲜分享拼盘', '鲜活大虾加购份'
);

-- ---------------------------------------------------------------
-- 核对结果
-- ---------------------------------------------------------------
SELECT store_id, store_name, address, phone, business_hours, status FROM xy_store WHERE store_id = @store_id;
SELECT COUNT(*) AS `可预约时段数（应为6）` FROM xy_reservation_slot WHERE store_id = @store_id AND status = '0';
SELECT COUNT(*) AS `可用钓位数（应为18）`  FROM xy_seat             WHERE store_id = @store_id AND status = '0';
SELECT plan_id, plan_name, amount, duration_days, status FROM xy_membership_plan WHERE duration_days = 30;
SELECT COUNT(*) AS `在售商品数（应为0）`   FROM xy_product          WHERE status = '0';
