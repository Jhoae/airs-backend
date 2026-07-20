#!/usr/bin/env bash
set -euo pipefail

# Synchronize the Git-managed Caddyfile only after Caddy can parse it. Caddy
# keeps the prior in-memory configuration if the reload fails, and this script
# also restores the host file so a later container restart remains safe.
BACKEND_DIR="${AIRS_BACKEND_DIR:-/home/sogangairs/service/backend}"
COMPOSE_DIR="${AIRS_COMPOSE_DIR:-/home/sogangairs/service/compose}"
CADDY_CONTAINER="${AIRS_CADDY_CONTAINER:-airs-caddy}"
SOURCE_FILE="${BACKEND_DIR}/deploy/raspberry/Caddyfile"
TARGET_FILE="${COMPOSE_DIR}/Caddyfile"
CONTAINER_CANDIDATE="/tmp/airs-caddyfile-candidate-$$"

if [[ ! -f "${SOURCE_FILE}" ]]; then
  echo "Git-managed Caddyfile is missing: ${SOURCE_FILE}" >&2
  exit 1
fi

if [[ ! -f "${TARGET_FILE}" ]]; then
  echo "Active Caddyfile is missing: ${TARGET_FILE}" >&2
  exit 1
fi

if [[ "$(docker inspect -f '{{.State.Running}}' "${CADDY_CONTAINER}" 2>/dev/null || true)" != "true" ]]; then
  echo "Caddy container is not running: ${CADDY_CONTAINER}" >&2
  exit 1
fi

if cmp -s "${SOURCE_FILE}" "${TARGET_FILE}"; then
  echo "Caddyfile is already current."
  exit 0
fi

candidate_file="$(mktemp)"
backup_file="$(mktemp)"

cleanup() {
  rm -f "${candidate_file}" "${backup_file}"
  docker exec "${CADDY_CONTAINER}" rm -f "${CONTAINER_CANDIDATE}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

install -m 0644 "${SOURCE_FILE}" "${candidate_file}"
cp "${TARGET_FILE}" "${backup_file}"
docker cp "${candidate_file}" "${CADDY_CONTAINER}:${CONTAINER_CANDIDATE}"

# Parse the candidate before replacing the bind-mounted active configuration.
docker exec "${CADDY_CONTAINER}" caddy validate \
  --config "${CONTAINER_CANDIDATE}" \
  --adapter caddyfile

install -m 0644 "${SOURCE_FILE}" "${TARGET_FILE}"

if docker exec "${CADDY_CONTAINER}" caddy reload \
  --config /etc/caddy/Caddyfile \
  --adapter caddyfile; then
  echo "Caddyfile synchronized and reloaded."
  exit 0
fi

echo "Caddy reload failed; restoring the previous active Caddyfile." >&2
cp "${backup_file}" "${TARGET_FILE}"
docker exec "${CADDY_CONTAINER}" caddy reload \
  --config /etc/caddy/Caddyfile \
  --adapter caddyfile
exit 1
