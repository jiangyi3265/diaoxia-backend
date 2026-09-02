-- 已删除的福利钓专场保留报名、支付与退款历史，但不再占用同门店同日期。
-- 本脚本可重复执行。先建立新约束，再删除旧约束，迁移过程中始终防止重复创建。

SET SESSION lock_wait_timeout = 10;

SET @xy_active_event_date_column = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE xy_benefit_event ADD COLUMN active_event_date DATE GENERATED ALWAYS AS (CASE WHEN status = ''DELETED'' THEN NULL ELSE event_date END) STORED COMMENT ''未删除场次日期，用于释放已删除场次的日期占用'' AFTER status',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'xy_benefit_event'
    AND column_name = 'active_event_date'
);
PREPARE xy_active_event_date_column_stmt FROM @xy_active_event_date_column;
EXECUTE xy_active_event_date_column_stmt;
DEALLOCATE PREPARE xy_active_event_date_column_stmt;

SET @xy_active_event_date_column_valid = (
  SELECT IF(COUNT(*) = 1, 1, 0)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'xy_benefit_event'
    AND column_name = 'active_event_date'
    AND data_type = 'date'
    AND extra LIKE '%STORED GENERATED%'
    AND LOWER(generation_expression) LIKE '%status%'
    AND LOWER(generation_expression) LIKE '%deleted%'
    AND LOWER(generation_expression) LIKE '%event_date%'
);

SET @xy_active_event_date_data_errors = (
  SELECT COUNT(*)
  FROM xy_benefit_event
  WHERE (status = 'DELETED' AND active_event_date IS NOT NULL)
     OR (status <> 'DELETED' AND (active_event_date IS NULL OR active_event_date <> event_date))
);
SET @xy_active_event_date_column_valid = IF(
  @xy_active_event_date_column_valid = 1 AND @xy_active_event_date_data_errors = 0, 1, 0
);

SET @xy_non_deleted_duplicate_groups = (
  SELECT COUNT(*)
  FROM (
    SELECT store_id, event_date
    FROM xy_benefit_event
    WHERE status <> 'DELETED'
    GROUP BY store_id, event_date
    HAVING COUNT(*) > 1
  ) xy_duplicate_groups
);

SET @xy_active_event_date_unique = (
  SELECT IF(COUNT(*) = 0 AND @xy_active_event_date_column_valid = 1
      AND @xy_non_deleted_duplicate_groups = 0,
    'ALTER TABLE xy_benefit_event ADD UNIQUE KEY uk_xy_benefit_event_active_store_date (store_id, active_event_date)',
    'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'xy_benefit_event'
    AND index_name = 'uk_xy_benefit_event_active_store_date'
);
PREPARE xy_active_event_date_unique_stmt FROM @xy_active_event_date_unique;
EXECUTE xy_active_event_date_unique_stmt;
DEALLOCATE PREPARE xy_active_event_date_unique_stmt;

SET @xy_active_event_date_unique_valid = (
  SELECT IF(COUNT(*) = 2
      AND MIN(non_unique) = 0
      AND GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') = 'store_id,active_event_date', 1, 0)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'xy_benefit_event'
    AND index_name = 'uk_xy_benefit_event_active_store_date'
);

SET @xy_legacy_event_date_unique_exists = (
  SELECT IF(COUNT(DISTINCT index_name) > 0, 1, 0)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'xy_benefit_event'
    AND index_name = 'uk_xy_benefit_event_store_date'
);
SET @xy_legacy_event_date_unique_valid = (
  SELECT IF(@xy_legacy_event_date_unique_exists = 0 OR (
      COUNT(*) = 2
      AND MIN(non_unique) = 0
      AND GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') = 'store_id,event_date'), 1, 0)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'xy_benefit_event'
    AND index_name = 'uk_xy_benefit_event_store_date'
);

SET @xy_legacy_event_date_unique = (
  SELECT IF(COUNT(*) > 0
      AND @xy_active_event_date_column_valid = 1
      AND @xy_active_event_date_unique_valid = 1
      AND @xy_legacy_event_date_unique_valid = 1,
    'ALTER TABLE xy_benefit_event DROP INDEX uk_xy_benefit_event_store_date',
    'SELECT 1')
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'xy_benefit_event'
    AND index_name = 'uk_xy_benefit_event_store_date'
);
PREPARE xy_legacy_event_date_unique_stmt FROM @xy_legacy_event_date_unique;
EXECUTE xy_legacy_event_date_unique_stmt;
DEALLOCATE PREPARE xy_legacy_event_date_unique_stmt;

SET @xy_legacy_event_date_unique_remaining = (
  SELECT COUNT(DISTINCT index_name)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'xy_benefit_event'
    AND index_name = 'uk_xy_benefit_event_store_date'
);

SELECT
  @xy_active_event_date_column_valid AS active_date_column_ready,
  @xy_active_event_date_unique_valid AS active_date_unique_ready,
  @xy_non_deleted_duplicate_groups AS active_duplicate_groups,
  @xy_legacy_event_date_unique_remaining AS legacy_unique_remaining;

SET @xy_benefit_event_date_reuse_ready = IF(
  @xy_active_event_date_column_valid = 1
  AND @xy_active_event_date_unique_valid = 1
  AND @xy_non_deleted_duplicate_groups = 0
  AND @xy_legacy_event_date_unique_remaining = 0, 1, 0
);
SET @xy_benefit_event_date_reuse_assertion = IF(
  @xy_benefit_event_date_reuse_ready = 1,
  'SELECT 1',
  'SELECT * FROM xy_benefit_event_date_reuse_MIGRATION_VALIDATION_FAILED'
);
PREPARE xy_benefit_event_date_reuse_assertion_stmt FROM @xy_benefit_event_date_reuse_assertion;
EXECUTE xy_benefit_event_date_reuse_assertion_stmt;
DEALLOCATE PREPARE xy_benefit_event_date_reuse_assertion_stmt;
