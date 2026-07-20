#!/usr/bin/env bash
set -euo pipefail

# 보존 중인 원본 데이터로 파생 1일 CO2 point를 다시 만든다.
# 정기 집계는 InfluxDB Task가 담당하므로 이 스크립트는 수동 실행만 한다.
INFLUX_CONTAINER="${INFLUX_CONTAINER:-airs-influxdb}"
INFLUX_ORG="${INFLUX_ORG:-airs}"
RAW_BUCKET="${INFLUX_RAW_BUCKET:-airs}"
ROLLUP_BUCKET="${INFLUX_ROLLUP_BUCKET:-airs_rollup}"
START="${1:?start UTC day is required}"
END="${2:?end UTC day is required}"

is_utc_day() {
  local timestamp="$1"
  [[ "$(date -u -d "${timestamp}" +%Y-%m-%dT00:00:00Z 2>/dev/null || true)" == "${timestamp}" ]]
}

if ! is_utc_day "${START}" || ! is_utc_day "${END}"; then
  echo "Use exact UTC day boundaries, for example 2026-07-20T00:00:00Z." >&2
  exit 1
fi

start_epoch="$(date -u -d "${START}" +%s)"
end_epoch="$(date -u -d "${END}" +%s)"
current_day_epoch="$(date -u -d "$(date -u +%Y-%m-%dT00:00:00Z)" +%s)"

if (( start_epoch >= end_epoch )); then
  echo "The end day must be later than the start day." >&2
  exit 1
fi

if (( end_epoch > current_day_epoch )); then
  echo "The end day must not include the current partial UTC day." >&2
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
    |> aggregateWindow(every: 1d, fn: mean, createEmpty: false)
    |> set(key: "_field", value: "co2_mean")

co2Min =
  rawCo2
    |> aggregateWindow(every: 1d, fn: min, createEmpty: false)
    |> set(key: "_field", value: "co2_min")

co2Max =
  rawCo2
    |> aggregateWindow(every: 1d, fn: max, createEmpty: false)
    |> set(key: "_field", value: "co2_max")

co2Count =
  rawCo2
    |> aggregateWindow(every: 1d, fn: count, createEmpty: false)
    |> set(key: "_field", value: "co2_count")

co2Mean
  |> set(key: "_measurement", value: "sensor_rollup_1d")
  |> to(bucket: "${ROLLUP_BUCKET}", tagColumns: ["node_id"])

co2Min
  |> set(key: "_measurement", value: "sensor_rollup_1d")
  |> to(bucket: "${ROLLUP_BUCKET}", tagColumns: ["node_id"])

co2Max
  |> set(key: "_measurement", value: "sensor_rollup_1d")
  |> to(bucket: "${ROLLUP_BUCKET}", tagColumns: ["node_id"])

co2Count
  |> set(key: "_measurement", value: "sensor_rollup_1d")
  |> to(bucket: "${ROLLUP_BUCKET}", tagColumns: ["node_id"])
FLUX

echo "Backfilled daily CO2 rollups for [${START}, ${END})."
