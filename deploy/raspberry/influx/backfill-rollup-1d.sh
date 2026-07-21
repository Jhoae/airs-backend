#!/usr/bin/env bash
set -euo pipefail

# 보존 중인 원본 데이터로 파생 1일 온도·습도·CO2 point를 다시 만든다.
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

# 한 metric의 mean·min·max·count를 같은 rollup point field로 기록한다.
backfill_metric() {
  local source_field="$1"
  local rollup_prefix="$2"

  cat <<FLUX | docker exec -i "${INFLUX_CONTAINER}" influx query --org "${INFLUX_ORG}" -
raw =
  from(bucket: "${RAW_BUCKET}")
    |> range(start: time(v: "${START}"), stop: time(v: "${END}"))
    |> filter(fn: (r) => r._measurement == "sensor_data")
    |> filter(fn: (r) => r._field == "${source_field}")
    |> keep(columns: ["_time", "_value", "node_id"])

mean = raw
  |> aggregateWindow(every: 1d, fn: mean, createEmpty: false)
  |> set(key: "_field", value: "${rollup_prefix}_mean")

min = raw
  |> aggregateWindow(every: 1d, fn: min, createEmpty: false)
  |> set(key: "_field", value: "${rollup_prefix}_min")

max = raw
  |> aggregateWindow(every: 1d, fn: max, createEmpty: false)
  |> set(key: "_field", value: "${rollup_prefix}_max")

count = raw
  |> aggregateWindow(every: 1d, fn: count, createEmpty: false)
  |> set(key: "_field", value: "${rollup_prefix}_count")

// 같은 measurement·node_id·시각에 기록된 field는 하나의 point로 병합된다.
mean |> set(key: "_measurement", value: "sensor_rollup_1d") |> to(bucket: "${ROLLUP_BUCKET}", tagColumns: ["node_id"])
min |> set(key: "_measurement", value: "sensor_rollup_1d") |> to(bucket: "${ROLLUP_BUCKET}", tagColumns: ["node_id"])
max |> set(key: "_measurement", value: "sensor_rollup_1d") |> to(bucket: "${ROLLUP_BUCKET}", tagColumns: ["node_id"])
count |> set(key: "_measurement", value: "sensor_rollup_1d") |> to(bucket: "${ROLLUP_BUCKET}", tagColumns: ["node_id"])
FLUX
}

# raw field와 rollup field 접두어를 명시해 정기 Task와 저장 계약을 맞춘다.
backfill_metric "temperature_c" "temperature"
backfill_metric "humidity_pct" "humidity"
backfill_metric "co2_ppm" "co2"

echo "Backfilled daily temperature, humidity, and CO2 rollups for [${START}, ${END})."
