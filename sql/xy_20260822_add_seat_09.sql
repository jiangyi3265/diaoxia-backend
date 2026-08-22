-- 2026-08-22：补建 9 号钓位。
--
-- 上线脚本按甲方手绘图纸建了 1-8、10-18 共 17 个钓位（图纸上没有 9 号）。
-- 甲方在小程序上核对后确认实际是 18 个钓位，缺的就是 9 号，这里补回，
-- 补完为 1-18 连号共 18 个。
--
-- 幂等，可重复执行（uk_xy_seat_code 保证不会重复插入）。

SET NAMES utf8mb4;

INSERT IGNORE INTO xy_seat(store_id, seat_code, zone_name, status, sort_order)
SELECT store_id, '9', NULL, '0', 9
FROM xy_store
WHERE store_name = '成泰钓虾俱乐部';

-- 核对：应列出 1-18 共 18 行，且全部 status='0'
SELECT s.seat_code, s.status, s.sort_order
FROM xy_seat s
JOIN xy_store st ON st.store_id = s.store_id
WHERE st.store_name = '成泰钓虾俱乐部'
ORDER BY s.sort_order;

SELECT COUNT(*) AS `可用钓位数（应为18）`
FROM xy_seat s
JOIN xy_store st ON st.store_id = s.store_id
WHERE st.store_name = '成泰钓虾俱乐部' AND s.status = '0';
