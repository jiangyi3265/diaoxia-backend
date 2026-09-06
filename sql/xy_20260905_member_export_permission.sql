-- 会员名单 Excel 导出权限。文件包含手机号，仅向明确授权的后台角色开放。
-- 本脚本可重复执行。

SET @xy_member_export_menu_id_conflicts = (
  SELECT COUNT(*)
  FROM sys_menu
  WHERE menu_id = 10018
    AND COALESCE(perms, '') <> 'xy:member:export'
);
SET @xy_member_export_perm_conflicts = (
  SELECT COUNT(*)
  FROM sys_menu
  WHERE perms = 'xy:member:export'
    AND menu_id <> 10018
);
SET @xy_member_export_precheck = IF(
  @xy_member_export_menu_id_conflicts = 0 AND @xy_member_export_perm_conflicts = 0,
  'SELECT 1',
  'SELECT * FROM xy_member_export_permission_MIGRATION_CONFLICT'
);
PREPARE xy_member_export_precheck_stmt FROM @xy_member_export_precheck;
EXECUTE xy_member_export_precheck_stmt;
DEALLOCATE PREPARE xy_member_export_precheck_stmt;

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, `query`, route_name,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT
  10018, '会员数据导出', 10002, 2, '', '', '', '',
  1, 0, 'F', '0', '0', 'xy:member:export', '#', 'admin', NOW(), '导出包含手机号的会员名单'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 10018);

UPDATE sys_menu
SET menu_name = '会员数据导出', parent_id = 10002, order_num = 2,
    menu_type = 'F', visible = '0', status = '0', perms = 'xy:member:export',
    remark = '导出包含手机号的会员名单'
WHERE menu_id = 10018;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, 10018
WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 10018);

SET @xy_member_export_permission_ready = (
  SELECT IF(COUNT(*) = 1, 1, 0)
  FROM sys_menu
  WHERE menu_id = 10018
    AND parent_id = 10002
    AND menu_type = 'F'
    AND status = '0'
    AND perms = 'xy:member:export'
);
SET @xy_member_export_admin_grant_ready = (
  SELECT IF(COUNT(*) = 1, 1, 0)
  FROM sys_role_menu
  WHERE role_id = 1 AND menu_id = 10018
);

SELECT
  @xy_member_export_permission_ready AS member_export_permission_ready,
  @xy_member_export_admin_grant_ready AS admin_role_grant_ready;

SET @xy_member_export_assertion = IF(
  @xy_member_export_permission_ready = 1 AND @xy_member_export_admin_grant_ready = 1,
  'SELECT 1',
  'SELECT * FROM xy_member_export_permission_MIGRATION_VALIDATION_FAILED'
);
PREPARE xy_member_export_assertion_stmt FROM @xy_member_export_assertion;
EXECUTE xy_member_export_assertion_stmt;
DEALLOCATE PREPARE xy_member_export_assertion_stmt;
