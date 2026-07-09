SET @alter_alert_type_sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'alerts'
        AND column_name = 'alert_type'
    ),
    'ALTER TABLE alerts MODIFY alert_type VARCHAR(60) NOT NULL',
    'SELECT 1'
  )
);
PREPARE alter_alert_type_stmt FROM @alter_alert_type_sql;
EXECUTE alter_alert_type_stmt;
DEALLOCATE PREPARE alter_alert_type_stmt;
