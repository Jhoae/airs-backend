#!/usr/bin/env bash
set -euo pipefail

# 라즈베리파이 호스트에서 실행하며, 인증 토큰은 기존 InfluxDB 컨테이너 설정을 사용한다.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFLUX_CONTAINER="${INFLUX_CONTAINER:-airs-influxdb}"
INFLUX_ORG="${INFLUX_ORG:-airs}"
ROLLUP_BUCKET="${INFLUX_ROLLUP_BUCKET:-airs_rollup}"
TASK_NAMES=("airs-rollup-1h" "airs-rollup-1d")
TASK_FILES=(
  "${SCRIPT_DIR}/tasks/sensor-rollup-1h.flux"
  "${SCRIPT_DIR}/tasks/sensor-rollup-1d.flux"
)

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

for index in "${!TASK_NAMES[@]}"; do
  task_name="${TASK_NAMES[$index]}"
  task_file="${TASK_FILES[$index]}"
  container_task_file="/tmp/${task_name}.flux"

  if [[ ! -f "${task_file}" ]]; then
    echo "Flux task file is missing: ${task_file}" >&2
    exit 1
  fi

  task_id="$({
    docker exec "${INFLUX_CONTAINER}" influx task list --org "${INFLUX_ORG}" --json \
      | jq -r --arg taskName "${task_name}" '[.[] | select(.name == $taskName)] | if length == 1 then .[0].id else empty end'
  })"
  task_count="$({
    docker exec "${INFLUX_CONTAINER}" influx task list --org "${INFLUX_ORG}" --json \
      | jq --arg taskName "${task_name}" '[.[] | select(.name == $taskName)] | length'
  })"

  if [[ "${task_count}" -gt 1 ]]; then
    echo "Expected at most one task named ${task_name}, found ${task_count}." >&2
    exit 1
  fi

  docker cp "${task_file}" "${INFLUX_CONTAINER}:${container_task_file}"

  if [[ -z "${task_id}" ]]; then
    docker exec "${INFLUX_CONTAINER}" influx task create \
      --org "${INFLUX_ORG}" \
      --file "${container_task_file}"
    echo "Created InfluxDB task: ${task_name}"
  else
    docker exec "${INFLUX_CONTAINER}" influx task update \
      --id "${task_id}" \
      --file "${container_task_file}"
    echo "Updated InfluxDB task: ${task_name} (${task_id})"
  fi

  docker exec "${INFLUX_CONTAINER}" rm -f "${container_task_file}"
done
