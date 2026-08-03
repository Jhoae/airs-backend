#!/bin/sh
# MQTT ingest·outbox publisher·cleanup 동시 실행 뒤 핵심 불변식을 확인합니다.
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
compose_file="$script_dir/docker-compose.yml"

expected_nodes=${EXPECTED_NODE_COUNT:-1000}
expected_sequence=${EXPECTED_SEQUENCE:-24}
expected_points=${EXPECTED_POINT_COUNT:-24000}
expect_cleanup_empty=${EXPECT_CLEANUP_EMPTY:-false}
timeout_seconds=${VERIFY_TIMEOUT_SECONDS:-180}
poll_seconds=${VERIFY_POLL_SECONDS:-2}

mysql_scalar() {
  docker compose -f "$compose_file" exec -T mysql \
    mysql -N -uroot -pairs-stage-root-password airs_stage -e "$1" 2>/dev/null
}

influx_points() {
  docker compose -f "$compose_file" exec -T influxdb influx query --raw \
    --org airs_stage \
    --token airs-stage-influx-token-for-local-tests-only \
    'from(bucket: "airs_stage_raw")
      |> range(start: -6h)
      |> filter(fn: (r) => r._measurement == "sensor_data")
      |> filter(fn: (r) => r._field == "co2_ppm")
      |> count()
      |> group()
      |> sum(column: "_value")' 2>/dev/null \
    | awk -F, 'NF > 1 { gsub(/\r/, "", $NF); if ($NF ~ /^[0-9]+$/) value=$NF } END { print value+0 }'
}

started_at=$(date +%s)
while :; do
  points=$(influx_points)
  pending=$(mysql_scalar 'SELECT COUNT(*) FROM telemetry_outbox WHERE status="PENDING";')
  retry=$(mysql_scalar 'SELECT COUNT(*) FROM telemetry_outbox WHERE status="RETRY";')
  dead=$(mysql_scalar 'SELECT COUNT(*) FROM telemetry_outbox WHERE status="DEAD";')
  completed=$(mysql_scalar 'SELECT COUNT(*) FROM telemetry_outbox WHERE status="COMPLETED";')

  echo "reliability wait: points=$points pending=$pending retry=$retry completed=$completed dead=$dead"

  cleanup_ready=true
  if [ "$expect_cleanup_empty" = "true" ] && [ "$completed" -ne 0 ]; then
    cleanup_ready=false
  fi
  if [ "$points" -eq "$expected_points" ] \
      && [ "$pending" -eq 0 ] \
      && [ "$retry" -eq 0 ] \
      && [ "$dead" -eq 0 ] \
      && [ "$cleanup_ready" = "true" ]; then
    break
  fi

  now=$(date +%s)
  if [ $((now - started_at)) -ge "$timeout_seconds" ]; then
    echo "reliability 결과 대기 시간이 초과되었습니다." >&2
    exit 1
  fi
  sleep "$poll_seconds"
done

state_count=$(mysql_scalar 'SELECT COUNT(*) FROM telemetry_ingestion_states;')
matching_sequence=$(mysql_scalar "SELECT COUNT(*) FROM telemetry_ingestion_states WHERE last_sequence_no=$expected_sequence;")
node_snapshots=$(mysql_scalar 'SELECT COUNT(*) FROM node_status_snapshots;')
space_snapshots=$(mysql_scalar 'SELECT COUNT(*) FROM space_status_snapshots;')
sequence_min=$(mysql_scalar 'SELECT COALESCE(MIN(last_sequence_no),0) FROM telemetry_ingestion_states;')
sequence_max=$(mysql_scalar 'SELECT COALESCE(MAX(last_sequence_no),0) FROM telemetry_ingestion_states;')

if [ "$state_count" -ne "$expected_nodes" ] \
    || [ "$matching_sequence" -ne "$expected_nodes" ] \
    || [ "$node_snapshots" -ne "$expected_nodes" ] \
    || [ "$space_snapshots" -ne "$expected_nodes" ] \
    || [ "$sequence_min" -ne "$expected_sequence" ] \
    || [ "$sequence_max" -ne "$expected_sequence" ]; then
  echo "MySQL sequence 또는 snapshot 불변식이 맞지 않습니다." >&2
  exit 1
fi

backend_errors=$(docker compose -f "$compose_file" logs --no-color backend 2>&1 \
  | grep -Eic 'deadlock|lock wait timeout|OutOfMemoryError' || true)
broker_errors=$(docker compose -f "$compose_file" logs --no-color mosquitto 2>&1 \
  | grep -Eic 'dropping message|denied|out of memory' || true)
restarts=$(docker compose -f "$compose_file" ps -q backend mysql influxdb mosquitto \
  | xargs docker inspect --format '{{.RestartCount}}' \
  | awk '{ total += $1 } END { print total+0 }')

if [ "$backend_errors" -ne 0 ] || [ "$broker_errors" -ne 0 ] || [ "$restarts" -ne 0 ]; then
  echo "deadlock·broker drop 또는 container restart가 발견되었습니다." >&2
  exit 1
fi

echo "reliability 검증 통과: nodes=$state_count sequence=$sequence_min points=$points snapshots=$node_snapshots/$space_snapshots pending=$pending retry=$retry completed=$completed dead=$dead restarts=$restarts"
