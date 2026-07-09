SET @add_status_sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name = 'campus_admins'
    )
    AND NOT EXISTS (
      SELECT 1
      FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'campus_admins'
        AND column_name = 'status'
    ),
    'ALTER TABLE campus_admins ADD COLUMN status VARCHAR(20) NULL',
    'SELECT 1'
  )
);
PREPARE add_status_stmt FROM @add_status_sql;
EXECUTE add_status_stmt;
DEALLOCATE PREPARE add_status_stmt;

SET @copy_approved_sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'campus_admins'
        AND column_name = 'approved'
    )
    AND EXISTS (
      SELECT 1
      FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'campus_admins'
        AND column_name = 'status'
    ),
    'UPDATE campus_admins SET status = CASE WHEN approved = TRUE THEN ''APPROVED'' ELSE ''PENDING'' END WHERE status IS NULL',
    'SELECT 1'
  )
);
PREPARE copy_approved_stmt FROM @copy_approved_sql;
EXECUTE copy_approved_stmt;
DEALLOCATE PREPARE copy_approved_stmt;

SET @fill_status_sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'campus_admins'
        AND column_name = 'status'
    ),
    'UPDATE campus_admins SET status = ''PENDING'' WHERE status IS NULL',
    'SELECT 1'
  )
);
PREPARE fill_status_stmt FROM @fill_status_sql;
EXECUTE fill_status_stmt;
DEALLOCATE PREPARE fill_status_stmt;

SET @not_null_status_sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'campus_admins'
        AND column_name = 'status'
    ),
    'ALTER TABLE campus_admins MODIFY status VARCHAR(20) NOT NULL',
    'SELECT 1'
  )
);
PREPARE not_null_status_stmt FROM @not_null_status_sql;
EXECUTE not_null_status_stmt;
DEALLOCATE PREPARE not_null_status_stmt;

SET @drop_approved_sql = (
  SELECT IF(
    EXISTS (
      SELECT 1
      FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'campus_admins'
        AND column_name = 'approved'
    ),
    'ALTER TABLE campus_admins DROP COLUMN approved',
    'SELECT 1'
  )
);
PREPARE drop_approved_stmt FROM @drop_approved_sql;
EXECUTE drop_approved_stmt;
DEALLOCATE PREPARE drop_approved_stmt;
