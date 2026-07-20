#!/usr/bin/env bash
set -euo pipefail

# This script runs on the Raspberry Pi host. The InfluxDB container already has
# its authenticated CLI configuration, so no token is copied into this repository.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFLUX_CONTAINER="${INFLUX_CONTAINER:-airs-influxdb}"
INFLUX_ORG="${INFLUX_ORG:-airs}"
ROLLUP_BUCKET="${INFLUX_ROLLUP_BUCKET:-airs_rollup}"
TASK_NAME="airs-rollup-1h"
TASK_FILE="${SCRIPT_DIR}/tasks/sensor-rollup-1h.flux"
CONTAINER_TASK_FILE="/tmp/${TASK_NAME}.flux"

if [[ ! -f "${TASK_FILE}" ]]; then
  echo "Flux task file is missing: ${TASK_FILE}" >&2
  exit 1
fi

if ! docker inspect "${INFLUX_CONTAINER}" >/dev/null 2>&1; then
  echo "InfluxDB container does not exist: ${INFLUX_CONTAINER}" >&2
  exit 1
fi

if [[ "$(docker inspect -f '{{.State.Running}}' "${INFLUX_CONTAINER}")" != "true" ]]; then
  echo "InfluxDB container is not running: ${INFLUX_CONTAINER}" >&2
  exit 1
fi

bucket_count="$({
  docker exec "${INFLUX_CONTAINER}" influx bucket list --org "${INFLUX_ORG}" --json \
    | jq --arg bucket "${ROLLUP_BUCKET}" '[.[] | select(.name == $bucket)] | length'
})"

if [[ "${bucket_count}" == "0" ]]; then
  docker exec "${INFLUX_CONTAINER}" influx bucket create \
    --org "${INFLUX_ORG}" \
    --name "${ROLLUP_BUCKET}" \
    --retention 0 \
    --description "Derived AIRS CO2 rollups. Rebuildable from raw sensor_data."
elif [[ "${bucket_count}" != "1" ]]; then
  echo "Expected one rollup bucket named ${ROLLUP_BUCKET}, found ${bucket_count}." >&2
  exit 1
fi

task_id="$({
  docker exec "${INFLUX_CONTAINER}" influx task list --org "${INFLUX_ORG}" --json \
    | jq -r --arg taskName "${TASK_NAME}" '[.[] | select(.name == $taskName)] | if length == 1 then .[0].id else empty end'
})"
task_count="$({
  docker exec "${INFLUX_CONTAINER}" influx task list --org "${INFLUX_ORG}" --json \
    | jq --arg taskName "${TASK_NAME}" '[.[] | select(.name == $taskName)] | length'
})"

if [[ "${task_count}" -gt 1 ]]; then
  echo "Expected at most one task named ${TASK_NAME}, found ${task_count}." >&2
  exit 1
fi

docker cp "${TASK_FILE}" "${INFLUX_CONTAINER}:${CONTAINER_TASK_FILE}"
trap 'docker exec "${INFLUX_CONTAINER}" rm -f "${CONTAINER_TASK_FILE}" >/dev/null 2>&1 || true' EXIT

if [[ -z "${task_id}" ]]; then
  docker exec "${INFLUX_CONTAINER}" influx task create \
    --org "${INFLUX_ORG}" \
    --file "${CONTAINER_TASK_FILE}"
  echo "Created InfluxDB task: ${TASK_NAME}"
else
  docker exec "${INFLUX_CONTAINER}" influx task update \
    --id "${task_id}" \
    --file "${CONTAINER_TASK_FILE}"
  echo "Updated InfluxDB task: ${TASK_NAME} (${task_id})"
fi
