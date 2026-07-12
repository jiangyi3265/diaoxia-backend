-- 商城会员折扣、商品排除开关、预约订阅提醒（可重复执行）
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'xy_product' AND column_name = 'member_discount_enabled') = 0,
    'ALTER TABLE xy_product ADD COLUMN member_discount_enabled TINYINT NOT NULL DEFAULT 1',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'xy_order' AND column_name = 'discount_amount') = 0,
    'ALTER TABLE xy_order ADD COLUMN discount_amount DECIMAL(10,2) NOT NULL DEFAULT 0',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'xy_order' AND column_name = 'member_discount_rate') = 0,
    'ALTER TABLE xy_order ADD COLUMN member_discount_rate DECIMAL(5,4) NOT NULL DEFAULT 1.0000',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS xy_business_setting (
    setting_key VARCHAR(64) NOT NULL,
    setting_value VARCHAR(255) NOT NULL,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO xy_business_setting(setting_key, setting_value)
VALUES ('member_product_discount_rate', '0.95')
ON DUPLICATE KEY UPDATE setting_value = setting_value;

CREATE TABLE IF NOT EXISTS xy_reservation_notification_record (
    notification_id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_id BIGINT NOT NULL,
    reminder_type VARCHAR(24) NOT NULL,
    scheduled_for DATETIME NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    error_message VARCHAR(500) DEFAULT NULL,
    sent_time DATETIME DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (notification_id),
    UNIQUE KEY uk_xy_reservation_reminder (reservation_id, reminder_type),
    KEY idx_xy_reservation_reminder_status (status, scheduled_for),
    CONSTRAINT fk_xy_reservation_reminder_reservation
        FOREIGN KEY (reservation_id) REFERENCES xy_reservation(reservation_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
