-- MQTT 처리 완료 상태를 Redis보다 내구성 있는 MySQL에 노드별 한 행으로 보관한다.
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- MySQL transaction이 확정한 telemetry를 InfluxDB로 재전달할 짧은 수명의 outbox다.
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
    CONSTRAINT uk_telemetry_outbox_event_key UNIQUE (event_key),
    INDEX idx_telemetry_outbox_pending (status, next_retry_at, claimed_at, id),
    INDEX idx_telemetry_outbox_completed (status, completed_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
