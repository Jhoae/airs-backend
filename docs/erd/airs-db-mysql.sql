CREATE TABLE campuses (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(150) NOT NULL,
  latitude DECIMAL(10, 7) NULL,
  longitude DECIMAL(10, 7) NULL,
  radius_meter INT NULL,
  created_at DATETIME NOT NULL,
  deleted_at DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_campuses_name (name)
);

CREATE TABLE buildings (
  id BIGINT NOT NULL AUTO_INCREMENT,
  campus_id BIGINT NOT NULL,
  name VARCHAR(150) NOT NULL,
  created_at DATETIME NOT NULL,
  deleted_at DATETIME NULL,
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
  space_type VARCHAR(40) NOT NULL,
  latitude DECIMAL(10, 7) NULL,
  longitude DECIMAL(10, 7) NULL,
  created_at DATETIME NOT NULL,
  deleted_at DATETIME NULL,
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
  nickname VARCHAR(10) NOT NULL,
  email VARCHAR(50) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  phone VARCHAR(20) NULL,
  role VARCHAR(20) NOT NULL,
  created_at DATETIME NOT NULL,
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
  approved BOOLEAN NOT NULL,
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
  created_at DATETIME NOT NULL,
  deleted_at DATETIME NULL,
  PRIMARY KEY (id)
);

CREATE TABLE node_installations (
  id BIGINT NOT NULL AUTO_INCREMENT,
  node_id VARCHAR(80) NOT NULL,
  space_id BIGINT NOT NULL,
  installed_by_user_id BIGINT NULL,
  installed_at DATETIME NOT NULL,
  is_active BOOLEAN NOT NULL,
  PRIMARY KEY (id),
  KEY idx_node_installations_node_active (node_id, is_active),
  KEY idx_node_installations_space_active (space_id, is_active),
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
  connection_status VARCHAR(30) NOT NULL,
  sensor_status VARCHAR(30) NOT NULL,
  dht22_status VARCHAR(30) NULL,
  scd41_status VARCHAR(30) NULL,
  wifi_rssi INT NULL,
  human_detected BOOLEAN NULL,
  last_seen_at DATETIME NULL,
  last_sensor_received_at DATETIME NULL,
  updated_at DATETIME NOT NULL,
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
  human_detected BOOLEAN NULL,
  occupancy_status VARCHAR(30) NULL,
  comfort_score DECIMAL(5, 2) NULL,
  last_updated_at DATETIME NULL,
  updated_at DATETIME NOT NULL,
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
  severity VARCHAR(30) NOT NULL,
  status VARCHAR(30) NOT NULL,
  audience VARCHAR(30) NOT NULL,
  title VARCHAR(180) NOT NULL,
  message VARCHAR(1000) NULL,
  metric_name VARCHAR(50) NULL,
  metric_value DECIMAL(12, 3) NULL,
  metric_unit VARCHAR(30) NULL,
  dedup_key VARCHAR(180) NOT NULL,
  started_at DATETIME NOT NULL,
  last_detected_at DATETIME NOT NULL,
  resolved_at DATETIME NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  PRIMARY KEY (id),
  KEY idx_alerts_campus_status (campus_id, status),
  KEY idx_alerts_space_status (space_id, status),
  KEY idx_alerts_node_status (node_id, status),
  CONSTRAINT fk_alerts_campus
    FOREIGN KEY (campus_id) REFERENCES campuses (id),
  CONSTRAINT fk_alerts_space
    FOREIGN KEY (space_id) REFERENCES spaces (id),
  CONSTRAINT fk_alerts_node
    FOREIGN KEY (node_id) REFERENCES airs_nodes (id)
);
