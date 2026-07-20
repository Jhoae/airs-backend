#!/usr/bin/env bash
set -euo pipefail

# Usage: ./verify-rollup-1h.sh node_01 2026-07-20T01:00:00Z 2026-07-20T02:00:00Z
# The end time is the rollup timestamp because aggregateWindow uses window end time.
INFLUX_CONTAINER="${INFLUX_CONTAINER:-airs-influxdb}"
INFLUX_ORG="${INFLUX_ORG:-airs}"
RAW_BUCKET="${INFLUX_RAW_BUCKET:-airs}"
ROLLUP_BUCKET="${INFLUX_ROLLUP_BUCKET:-airs_rollup}"
NODE_ID="${1:?node_id is required}"
HOUR_START="${2:?hour start (UTC ISO-8601) is required}"
HOUR_END="${3:?hour end (UTC ISO-8601) is required}"

if [[ ! "${NODE_ID}" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "node_id may contain only letters, digits, dot, underscore, and hyphen." >&2
  exit 1
fi

if ! date -u -d "${HOUR_START}" >/dev/null 2>&1 || ! date -u -d "${HOUR_END}" >/dev/null 2>&1; then
  echo "Use UTC ISO-8601 timestamps, for example 2026-07-20T02:00:00Z." >&2
  exit 1
fi

ROLLUP_RANGE_START="$(date -u -d "${HOUR_END} - 1 second" +%Y-%m-%dT%H:%M:%SZ)"
ROLLUP_RANGE_STOP="$(date -u -d "${HOUR_END} + 1 second" +%Y-%m-%dT%H:%M:%SZ)"

cat <<FLUX | docker exec -i "${INFLUX_CONTAINER}" influx query --org "${INFLUX_ORG}" -
raw =
  from(bucket: "${RAW_BUCKET}")
    |> range(start: time(v: "${HOUR_START}"), stop: time(v: "${HOUR_END}"))
    |> filter(fn: (r) => r._measurement == "sensor_data")
    |> filter(fn: (r) => r.node_id == "${NODE_ID}")
    |> filter(fn: (r) => r._field == "co2_ppm")

rawMean = raw |> mean() |> set(key: "metric", value: "raw_mean")
rawMin = raw |> min() |> set(key: "metric", value: "raw_min")
rawMax = raw |> max() |> set(key: "metric", value: "raw_max")
rawCount = raw |> count() |> set(key: "metric", value: "raw_count")

union(tables: [rawMean, rawMin, rawMax, rawCount])
  |> keep(columns: ["metric", "_value"])
  |> yield(name: "raw")

from(bucket: "${ROLLUP_BUCKET}")
  |> range(start: time(v: "${ROLLUP_RANGE_START}"), stop: time(v: "${ROLLUP_RANGE_STOP}"))
  |> filter(fn: (r) => r._measurement == "sensor_rollup_1h")
  |> filter(fn: (r) => r.node_id == "${NODE_ID}")
  |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
  |> keep(columns: ["_time", "co2_mean", "co2_min", "co2_max", "co2_count"])
  |> yield(name: "rollup")
FLUX
