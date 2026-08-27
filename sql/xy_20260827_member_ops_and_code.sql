-- 会员资料维护权限 + 4位动态会员码历史记录兼容
-- 幂等，可重复执行。

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

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, `query`, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 10015, '会员资料维护', 10002, 1, '', '', '', '', 1, 0, 'F', '0', '0', 'xy:member:edit', '#', 'admin', NOW(), '会员新增、修改和安全删除'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id=10015);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, 10015
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id=1 AND menu_id=10015);

SELECT
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='xy_member_visit' AND index_name='idx_xy_member_visit_code') AS member_code_index_ready,
  (SELECT COUNT(*) FROM sys_menu WHERE menu_id=10015 AND perms='xy:member:edit') AS member_edit_permission_ready;
