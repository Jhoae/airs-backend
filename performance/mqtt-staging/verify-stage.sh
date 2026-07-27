#!/bin/sh
# 로컬 staging의 MySQL snapshot·InfluxDB raw 적재·변화량 알림 결과를 함께 확인합니다.
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
compose_file="$script_dir/docker-compose.yml"

echo "[MySQL] 활성 설치·최신 snapshot·알림 집계"
docker compose -f "$compose_file" exec -T mysql \
  mysql -uroot -pairs-stage-root-password airs_stage -e '
    SELECT
      (SELECT COUNT(*) FROM node_installations WHERE is_active = TRUE) AS active_installations,
      (SELECT COUNT(*) FROM node_status_snapshots) AS node_snapshots,
      (SELECT COUNT(*) FROM space_status_snapshots) AS space_snapshots,
      (SELECT COUNT(*) FROM alerts WHERE status = "ACTIVE") AS active_alerts,
      (SELECT COUNT(*) FROM alerts WHERE alert_type = "CO2_RAPID_RISE" AND status = "ACTIVE") AS active_co2_rapid_rise_alerts;
  '

echo "[InfluxDB] 최근 한 시간 raw field 행 수"
docker compose -f "$compose_file" exec -T influxdb influx query \
  --org airs_stage \
  --token airs-stage-influx-token-for-local-tests-only \
  'from(bucket: "airs_stage_raw")
    |> range(start: -1h)
    |> filter(fn: (r) => r._measurement == "sensor_data")
    |> group(columns: ["_field"])
    |> count()
    |> keep(columns: ["_field", "_value"])'
