-- 预约暂停时段与公告。幂等执行，不修改已有预约。
CREATE TABLE IF NOT EXISTS xy_reservation_pause (
  pause_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '暂停预约ID',
  pause_batch_no VARCHAR(40) NOT NULL COMMENT '同次暂停批次号',
  store_id BIGINT NOT NULL COMMENT '门店ID',
  slot_id BIGINT NOT NULL COMMENT '暂停的时段ID',
  pause_date DATE NOT NULL COMMENT '暂停日期',
  announcement VARCHAR(500) NOT NULL COMMENT '小程序公告内容',
  create_by VARCHAR(64) DEFAULT NULL COMMENT '操作人',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (pause_id),
  UNIQUE KEY uk_xy_reservation_pause_slot (pause_date, slot_id),
  KEY idx_xy_reservation_pause_store_date (store_id, pause_date),
  KEY idx_xy_reservation_pause_batch (pause_batch_no),
  CONSTRAINT fk_xy_reservation_pause_store FOREIGN KEY (store_id) REFERENCES xy_store(store_id),
  CONSTRAINT fk_xy_reservation_pause_slot FOREIGN KEY (slot_id) REFERENCES xy_reservation_slot(slot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约暂停时段与公告';

SELECT
  (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema=DATABASE() AND table_name='xy_reservation_pause') AS reservation_pause_table_ready;
