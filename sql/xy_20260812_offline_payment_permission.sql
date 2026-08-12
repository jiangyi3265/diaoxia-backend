-- 线下收款/退款的独立操作权限。
-- 本迁移不修改业务表，可在现有生产库幂等执行。
SET NAMES utf8mb4;

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, `query`, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 10014, '线下收退款', 10007, 1, '', '', '', '',
       1, 0, 'F', '0', '0', 'xy:finance:collect', '#', 'admin', NOW(), '钓虾线下收退款权限'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 10014)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'xy:finance:collect');

INSERT INTO sys_role_menu(role_id, menu_id)
SELECT 1, m.menu_id
FROM sys_menu m
WHERE m.perms = 'xy:finance:collect'
  AND NOT EXISTS (
      SELECT 1
      FROM sys_role_menu rm
      WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id
  );
