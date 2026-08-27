-- 微信手机号验证与后台预建会员自动关联
-- 幂等，可重复执行；不直接合并历史会员，合并只在用户完成微信手机号授权后发生。

SET @xy_mobile_verified_column = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE xy_member ADD COLUMN mobile_verified_at DATETIME DEFAULT NULL COMMENT ''微信手机号验证时间'' AFTER mobile',
  'SELECT 1') FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='xy_member' AND column_name='mobile_verified_at');
PREPARE xy_mobile_verified_column_stmt FROM @xy_mobile_verified_column;
EXECUTE xy_mobile_verified_column_stmt;
DEALLOCATE PREPARE xy_mobile_verified_column_stmt;

SET @xy_member_mobile_index = (SELECT IF(COUNT(*)=0,
  'ALTER TABLE xy_member ADD KEY idx_xy_member_mobile (mobile)',
  'SELECT 1') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='xy_member' AND index_name='idx_xy_member_mobile');
PREPARE xy_member_mobile_index_stmt FROM @xy_member_mobile_index;
EXECUTE xy_member_mobile_index_stmt;
DEALLOCATE PREPARE xy_member_mobile_index_stmt;

SELECT
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='xy_member' AND column_name='mobile_verified_at') AS mobile_verified_column_ready,
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='xy_member' AND index_name='idx_xy_member_mobile') AS mobile_index_ready;
