-- 이 파일은 V1~V5 migration 적용 후 AIRS MySQL의 최종 구조를 설명한다.
-- 신규 DB 생성과 스키마 변경은 이 파일을 직접 실행하지 않고 Flyway migration으로 수행한다.

CREATE TABLE campuses (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(150) NOT NULL,
  latitude DECIMAL(10, 7) NULL,
  longitude DECIMAL(10, 7) NULL,
  radius_meter INT NULL,
  created_at DATETIME(6) NOT NULL,
  deleted_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_campuses_name (name)
);

CREATE TABLE buildings (
  id BIGINT NOT NULL AUTO_INCREMENT,
  campus_id BIGINT NOT NULL,
  name VARCHAR(150) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  deleted_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_buildings_campus_name (campus_id, name),
  CONSTRAINT fk_buildings_campus
    FOREIGN KEY (campus_id) REFERENCES campuses (id)
);

CREATE TABLE spaces (
  id BIGINT NOT NULL AUTO_INCREMENT,
  campus_id BIGINT NOT NULL,
  building_id BIGINT NOT NULL,
  code VARCHAR(40) NOT NULL,
  name VARCHAR(150) NOT NULL,
  floor_label VARCHAR(50) NULL,
  space_type ENUM('CLASSROOM','READING_ROOM','LAB','OFFICE','OTHER') NOT NULL,
  latitude DECIMAL(10, 7) NULL,
  longitude DECIMAL(10, 7) NULL,
  created_at DATETIME(6) NOT NULL,
  deleted_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_spaces_campus_code (campus_id, code),
  CONSTRAINT fk_spaces_campus
    FOREIGN KEY (campus_id) REFERENCES campuses (id),
  CONSTRAINT fk_spaces_building
    FOREIGN KEY (building_id) REFERENCES buildings (id)
);

CREATE TABLE users (
  id BIGINT NOT NULL AUTO_INCREMENT,
  campus_id BIGINT NULL,
  nickname VARCHAR(20) NOT NULL,
  email VARCHAR(50) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  phone VARCHAR(20) NULL,
  role ENUM('USER','ADMIN','ROOT_ADMIN') NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_email (email),
  CONSTRAINT fk_users_campus
    FOREIGN KEY (campus_id) REFERENCES campuses (id)
);

CREATE TABLE user_preferences (
  user_id BIGINT NOT NULL,
  preferred_temperature DECIMAL(4, 1) NULL,
  preferred_humidity DECIMAL(4, 1) NULL,
  wifi_ssid VARCHAR(100) NULL,
  PRIMARY KEY (user_id),
  CONSTRAINT fk_user_preferences_user
    FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE campus_admins (
  id BIGINT NOT NULL AUTO_INCREMENT,
  campus_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_campus_admins_campus_user (campus_id, user_id),
  CONSTRAINT fk_campus_admins_campus
    FOREIGN KEY (campus_id) REFERENCES campuses (id),
  CONSTRAINT fk_campus_admins_user
    FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE airs_nodes (
  id VARCHAR(80) NOT NULL,
  hardware_version VARCHAR(50) NULL,
  firmware_version VARCHAR(50) NULL,
  created_at DATETIME(6) NOT NULL,
  deleted_at DATETIME(6) NULL,
  PRIMARY KEY (id)
);

CREATE TABLE node_installations (
  id BIGINT NOT NULL AUTO_INCREMENT,
  node_id VARCHAR(80) NOT NULL,
  space_id BIGINT NOT NULL,
  installed_by_user_id BIGINT NULL,
  installed_at DATETIME(6) NOT NULL,
  is_active BIT(1) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_node_installations_node
    FOREIGN KEY (node_id) REFERENCES airs_nodes (id),
  CONSTRAINT fk_node_installations_space
    FOREIGN KEY (space_id) REFERENCES spaces (id),
  CONSTRAINT fk_node_installations_installed_by
    FOREIGN KEY (installed_by_user_id) REFERENCES users (id)
);

CREATE TABLE node_status_snapshots (
  id BIGINT NOT NULL AUTO_INCREMENT,
  node_id VARCHAR(80) NOT NULL,
  connection_status ENUM('ONLINE','WEAK','OFFLINE','UNKNOWN') NOT NULL,
  sensor_status ENUM('NORMAL','ABNORMAL','NO_DATA') NOT NULL,
  dht22_status VARCHAR(30) NULL,
  scd41_status VARCHAR(30) NULL,
  wifi_rssi INT NULL,
  human_detected BIT(1) NULL,
  last_seen_at DATETIME(6) NULL,
  last_sensor_received_at DATETIME(6) NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_node_status_snapshots_node (node_id),
  CONSTRAINT fk_node_status_snapshots_node
    FOREIGN KEY (node_id) REFERENCES airs_nodes (id)
);

CREATE TABLE space_status_snapshots (
  id BIGINT NOT NULL AUTO_INCREMENT,
  space_id BIGINT NOT NULL,
  representative_node_id VARCHAR(80) NULL,
  space_summary VARCHAR(30) NULL,
  co2_summary VARCHAR(30) NULL,
  temperature_summary VARCHAR(30) NULL,
  humidity_summary VARCHAR(30) NULL,
  occupancy_summary VARCHAR(30) NULL,
  comfort_summary VARCHAR(30) NULL,
  alert_count INT NOT NULL,
  temperature DECIMAL(5, 2) NULL,
  humidity DECIMAL(5, 2) NULL,
  co2_ppm INT NULL,
  human_detected BIT(1) NULL,
  occupancy_status ENUM('OCCUPIED','UNOCCUPIED','UNKNOWN') NULL,
  comfort_score DECIMAL(5, 2) NULL,
  last_updated_at DATETIME(6) NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_space_status_snapshots_space (space_id),
  CONSTRAINT fk_space_status_snapshots_space
    FOREIGN KEY (space_id) REFERENCES spaces (id),
  CONSTRAINT fk_space_status_snapshots_representative_node
    FOREIGN KEY (representative_node_id) REFERENCES airs_nodes (id)
);

CREATE TABLE alerts (
  id BIGINT NOT NULL AUTO_INCREMENT,
  campus_id BIGINT NOT NULL,
  space_id BIGINT NULL,
  node_id VARCHAR(80) NULL,
  alert_type VARCHAR(60) NOT NULL,
  severity ENUM('EMERGENCY','WARNING','INFO') NOT NULL,
  status ENUM('ACTIVE','RESOLVED') NOT NULL,
  audience ENUM('ADMIN','USER','ALL') NOT NULL,
  title VARCHAR(180) NOT NULL,
  message VARCHAR(1000) NULL,
  metric_name VARCHAR(50) NULL,
  metric_value DECIMAL(12, 3) NULL,
  metric_unit VARCHAR(30) NULL,
  dedup_key VARCHAR(180) NOT NULL,
  started_at DATETIME(6) NOT NULL,
  last_detected_at DATETIME(6) NOT NULL,
  resolved_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_alerts_campus
    FOREIGN KEY (campus_id) REFERENCES campuses (id),
  CONSTRAINT fk_alerts_space
    FOREIGN KEY (space_id) REFERENCES spaces (id),
  CONSTRAINT fk_alerts_node
    FOREIGN KEY (node_id) REFERENCES airs_nodes (id)
);

-- MQTT telemetry의 node별 처리 완료 상태와 재실 계산 상태를 유지한다.
CREATE TABLE telemetry_ingestion_states (
  node_id VARCHAR(80) NOT NULL,
  active_boot_id VARCHAR(64) NULL,
  last_sequence_no BIGINT NULL,
  previous_pir BIT(1) NULL,
  last_motion_at DATETIME(6) NULL,
  no_motion_started_at DATETIME(6) NULL,
  last_received_at DATETIME(6) NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (node_id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci;

-- MySQL에서 확정한 telemetry를 InfluxDB로 재전달하기 위한 짧은 수명의 outbox다.
CREATE TABLE telemetry_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_key VARCHAR(200) NOT NULL,
  node_id VARCHAR(80) NOT NULL,
  boot_id VARCHAR(64) NULL,
  sequence_no BIGINT NULL,
  received_at DATETIME(6) NOT NULL,
  point_payload_json LONGTEXT NOT NULL,
  schema_version INT NOT NULL,
  status VARCHAR(20) NOT NULL,
  retry_count INT NOT NULL,
  next_retry_at DATETIME(6) NULL,
  claimed_at DATETIME(6) NULL,
  last_error VARCHAR(1000) NULL,
  created_at DATETIME(6) NOT NULL,
  completed_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_telemetry_outbox_event_key
    UNIQUE (event_key),
  INDEX idx_telemetry_outbox_pending (
    status,
    next_retry_at,
    claimed_at,
    id
  ),
  INDEX idx_telemetry_outbox_completed (
    status,
    completed_at,
    id
  )
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci;
