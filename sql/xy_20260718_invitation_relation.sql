-- 2026-07-18：为既有生产库补充会员邀请归属关系。
-- 本脚本可重复执行；不会覆盖已经绑定的邀请人。

SET @xy_inviter_column = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE xy_member ADD COLUMN inviter_member_id BIGINT DEFAULT NULL COMMENT ''邀请人会员ID（首次绑定后不可改）'' AFTER invite_code',
  'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'xy_member'
    AND column_name = 'inviter_member_id');
PREPARE xy_inviter_column_stmt FROM @xy_inviter_column;
EXECUTE xy_inviter_column_stmt;
DEALLOCATE PREPARE xy_inviter_column_stmt;

SET @xy_inviter_index = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE xy_member ADD KEY idx_xy_member_inviter (inviter_member_id)',
  'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'xy_member'
    AND index_name = 'idx_xy_member_inviter');
PREPARE xy_inviter_index_stmt FROM @xy_inviter_index;
EXECUTE xy_inviter_index_stmt;
DEALLOCATE PREPARE xy_inviter_index_stmt;

SET @xy_inviter_fk = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE xy_member ADD CONSTRAINT fk_xy_member_inviter FOREIGN KEY (inviter_member_id) REFERENCES xy_member(member_id) ON DELETE SET NULL',
  'SELECT 1')
  FROM information_schema.table_constraints
  WHERE constraint_schema = DATABASE()
    AND table_name = 'xy_member'
    AND constraint_name = 'fk_xy_member_inviter');
PREPARE xy_inviter_fk_stmt FROM @xy_inviter_fk;
EXECUTE xy_inviter_fk_stmt;
DEALLOCATE PREPARE xy_inviter_fk_stmt;
