#!/usr/bin/env bash
set -euo pipefail

# 보존 중인 원본 데이터로 파생 1시간 CO2 point를 다시 만든다.
# 정기 집계는 InfluxDB Task가 담당하므로 이 스크립트는 수동 실행만 한다.
# 사용법: ./backfill-rollup-1h.sh 2026-07-05T02:00:00Z 2026-07-20T12:00:00Z
INFLUX_CONTAINER="${INFLUX_CONTAINER:-airs-influxdb}"
INFLUX_ORG="${INFLUX_ORG:-airs}"
RAW_BUCKET="${INFLUX_RAW_BUCKET:-airs}"
ROLLUP_BUCKET="${INFLUX_ROLLUP_BUCKET:-airs_rollup}"
START="${1:?start UTC hour is required}"
END="${2:?end UTC hour is required}"

is_utc_hour() {
  local timestamp="$1"
  [[ "$(date -u -d "${timestamp}" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || true)" == "${timestamp}" ]]
}

if ! is_utc_hour "${START}" || ! is_utc_hour "${END}"; then
  echo "Use exact UTC hour boundaries, for example 2026-07-20T02:00:00Z." >&2
  exit 1
fi

start_epoch="$(date -u -d "${START}" +%s)"
end_epoch="$(date -u -d "${END}" +%s)"
current_hour_epoch="$(date -u -d "$(date -u +%Y-%m-%dT%H:00:00Z)" +%s)"

if (( start_epoch >= end_epoch )); then
  echo "The end hour must be later than the start hour." >&2
  exit 1
fi

if (( end_epoch > current_hour_epoch )); then
  echo "The end hour must not include the current partial UTC hour." >&2
  exit 1
fi

if [[ "$(docker inspect -f '{{.State.Running}}' "${INFLUX_CONTAINER}" 2>/dev/null || true)" != "true" ]]; then
  echo "InfluxDB container is not running: ${INFLUX_CONTAINER}" >&2
  exit 1
fi

cat <<FLUX | docker exec -i "${INFLUX_CONTAINER}" influx query --org "${INFLUX_ORG}" -
rawCo2 =
  from(bucket: "${RAW_BUCKET}")
    |> range(start: time(v: "${START}"), stop: time(v: "${END}"))
    |> filter(fn: (r) => r._measurement == "sensor_data")
    |> filter(fn: (r) => r._field == "co2_ppm")
    |> keep(columns: ["_time", "_value", "node_id"])

co2Mean =
  rawCo2
    |> aggregateWindow(every: 1h, fn: mean, createEmpty: false)
    |> set(key: "_field", value: "co2_mean")

co2Min =
  rawCo2
    |> aggregateWindow(every: 1h, fn: min, createEmpty: false)
    |> set(key: "_field", value: "co2_min")

co2Max =
  rawCo2
    |> aggregateWindow(every: 1h, fn: max, createEmpty: false)
    |> set(key: "_field", value: "co2_max")

co2Count =
  rawCo2
    |> aggregateWindow(every: 1h, fn: count, createEmpty: false)
    |> set(key: "_field", value: "co2_count")

// measurement, node_id, 시각이 같으면 InfluxDB가 field를 한 point로 합친다.
// count는 정수이고 나머지는 실수이므로 별도 stream으로 저장한다.
co2Mean
  |> set(key: "_measurement", value: "sensor_rollup_1h")
  |> to(bucket: "${ROLLUP_BUCKET}", tagColumns: ["node_id"])

co2Min
  |> set(key: "_measurement", value: "sensor_rollup_1h")
  |> to(bucket: "${ROLLUP_BUCKET}", tagColumns: ["node_id"])

co2Max
  |> set(key: "_measurement", value: "sensor_rollup_1h")
  |> to(bucket: "${ROLLUP_BUCKET}", tagColumns: ["node_id"])

co2Count
  |> set(key: "_measurement", value: "sensor_rollup_1h")
  |> to(bucket: "${ROLLUP_BUCKET}", tagColumns: ["node_id"])
FLUX

echo "Backfilled hourly CO2 rollups for [${START}, ${END})."
