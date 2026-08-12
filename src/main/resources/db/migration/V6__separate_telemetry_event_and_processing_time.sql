-- 노드별 최신 순서와 함께 최신 event time을 보존한다.
ALTER TABLE telemetry_ingestion_states
    ADD COLUMN last_observed_at DATETIME(6) NULL AFTER last_sequence_no;

-- 노드 상태의 센서 측정 시각과 Spring 수신 시각을 분리한다.
ALTER TABLE node_status_snapshots
    ADD COLUMN last_sensor_observed_at DATETIME(6) NULL AFTER last_sensor_received_at;

UPDATE node_status_snapshots
SET last_sensor_observed_at = last_sensor_received_at
WHERE last_sensor_observed_at IS NULL;

-- 공간 최신 값의 event time과 processing time을 별도로 보존한다.
ALTER TABLE space_status_snapshots
    ADD COLUMN last_received_at DATETIME(6) NULL AFTER last_updated_at;

UPDATE space_status_snapshots
SET last_received_at = last_updated_at
WHERE last_received_at IS NULL;
