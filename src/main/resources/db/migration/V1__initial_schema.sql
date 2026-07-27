-- 빈 MySQL에서 AIRS의 최초 운영 스키마를 만든다.
CREATE TABLE campuses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(150) NOT NULL,
    latitude DECIMAL(10, 7) NULL,
    longitude DECIMAL(10, 7) NULL,
    radius_meter INT NULL,
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_campuses_name UNIQUE (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 건물은 하나의 캠퍼스에 속하며 같은 캠퍼스 안에서 이름이 중복될 수 없다.
CREATE TABLE buildings (
    id BIGINT NOT NULL AUTO_INCREMENT,
    campus_id BIGINT NOT NULL,
    name VARCHAR(150) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_buildings_campus_name UNIQUE (campus_id, name),
    CONSTRAINT fk_buildings_campus FOREIGN KEY (campus_id) REFERENCES campuses (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 공간은 캠퍼스와 건물 관계 및 화면에 표시할 위치 정보를 보관한다.
CREATE TABLE spaces (
    id BIGINT NOT NULL AUTO_INCREMENT,
    campus_id BIGINT NOT NULL,
    building_id BIGINT NOT NULL,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(150) NOT NULL,
    floor_label VARCHAR(50) NULL,
    space_type ENUM('CLASSROOM', 'READING_ROOM', 'LAB', 'OFFICE', 'OTHER') NOT NULL,
    latitude DECIMAL(10, 7) NULL,
    longitude DECIMAL(10, 7) NULL,
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_spaces_campus_code UNIQUE (campus_id, code),
    CONSTRAINT fk_spaces_campus FOREIGN KEY (campus_id) REFERENCES campuses (id),
    CONSTRAINT fk_spaces_building FOREIGN KEY (building_id) REFERENCES buildings (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 사용자는 선택적으로 소속 캠퍼스를 가지며 역할에 따라 API 접근 권한이 달라진다.
CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    nickname VARCHAR(20) NOT NULL,
    email VARCHAR(50) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    phone VARCHAR(20) NULL,
    campus_id BIGINT NULL,
    role ENUM('USER', 'ADMIN', 'ROOT_ADMIN') NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT fk_users_campus FOREIGN KEY (campus_id) REFERENCES campuses (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 사용자별 온습도 및 Wi-Fi 설정은 사용자 PK를 공유하는 일대일 관계다.
CREATE TABLE user_preferences (
    user_id BIGINT NOT NULL,
    preferred_temperature DECIMAL(4, 1) NULL,
    preferred_humidity DECIMAL(4, 1) NULL,
    wifi_ssid VARCHAR(100) NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_preferences_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- V3 이전 관리자 신청 상태는 승인 여부 Boolean으로 저장했다.
CREATE TABLE campus_admins (
    id BIGINT NOT NULL AUTO_INCREMENT,
    campus_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    approved BIT(1) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_campus_admins_campus_user UNIQUE (campus_id, user_id),
    CONSTRAINT fk_campus_admins_campus FOREIGN KEY (campus_id) REFERENCES campuses (id),
    CONSTRAINT fk_campus_admins_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- AIRS 노드는 펌웨어와 하드웨어 버전을 포함한 물리 장치 식별자다.
CREATE TABLE airs_nodes (
    id VARCHAR(80) NOT NULL,
    hardware_version VARCHAR(50) NULL,
    firmware_version VARCHAR(50) NULL,
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 노드 설치 이력은 활성 설치 한 건을 현재 공간과 연결한다.
CREATE TABLE node_installations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    node_id VARCHAR(80) NOT NULL,
    space_id BIGINT NOT NULL,
    installed_by_user_id BIGINT NULL,
    installed_at DATETIME(6) NOT NULL,
    is_active BIT(1) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_node_installations_node FOREIGN KEY (node_id) REFERENCES airs_nodes (id),
    CONSTRAINT fk_node_installations_space FOREIGN KEY (space_id) REFERENCES spaces (id),
    CONSTRAINT fk_node_installations_installed_by FOREIGN KEY (installed_by_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 노드별 최신 연결과 센서 수신 상태는 한 행으로 유지한다.
CREATE TABLE node_status_snapshots (
    id BIGINT NOT NULL AUTO_INCREMENT,
    node_id VARCHAR(80) NOT NULL,
    connection_status ENUM('ONLINE', 'WEAK', 'OFFLINE', 'UNKNOWN') NOT NULL,
    sensor_status ENUM('NORMAL', 'ABNORMAL', 'NO_DATA') NOT NULL,
    dht22_status VARCHAR(30) NULL,
    scd41_status VARCHAR(30) NULL,
    wifi_rssi INT NULL,
    human_detected BIT(1) NULL,
    last_seen_at DATETIME(6) NULL,
    last_sensor_received_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_node_status_snapshots_node UNIQUE (node_id),
    CONSTRAINT fk_node_status_snapshots_node FOREIGN KEY (node_id) REFERENCES airs_nodes (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 공간별 최신 대표 센서값과 계산 결과는 한 행으로 유지한다.
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
    occupancy_status ENUM('OCCUPIED', 'UNOCCUPIED', 'UNKNOWN') NULL,
    comfort_score DECIMAL(5, 2) NULL,
    last_updated_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_space_status_snapshots_space UNIQUE (space_id),
    CONSTRAINT fk_space_status_snapshots_space FOREIGN KEY (space_id) REFERENCES spaces (id),
    CONSTRAINT fk_space_status_snapshots_representative_node FOREIGN KEY (representative_node_id) REFERENCES airs_nodes (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- V4 이전 알림 유형은 고정된 MySQL ENUM으로 저장했다.
CREATE TABLE alerts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    campus_id BIGINT NOT NULL,
    space_id BIGINT NULL,
    node_id VARCHAR(80) NULL,
    alert_type ENUM('INFO', 'NODE_OFFLINE', 'SENSOR_ABNORMAL', 'VENTILATION_RECOMMENDED', 'WEAK_WIFI') NOT NULL,
    severity ENUM('EMERGENCY', 'WARNING', 'INFO') NOT NULL,
    status ENUM('ACTIVE', 'RESOLVED') NOT NULL,
    audience ENUM('ADMIN', 'USER', 'ALL') NOT NULL,
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
    CONSTRAINT fk_alerts_campus FOREIGN KEY (campus_id) REFERENCES campuses (id),
    CONSTRAINT fk_alerts_space FOREIGN KEY (space_id) REFERENCES spaces (id),
    CONSTRAINT fk_alerts_node FOREIGN KEY (node_id) REFERENCES airs_nodes (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- V2에서 제거할 과거 devices 테이블을 migration 이력 재현용으로만 만든다.
CREATE TABLE devices (
    node_id VARCHAR(100) NOT NULL,
    PRIMARY KEY (node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
