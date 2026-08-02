#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 hot|mixed|soak <1|2|5|10|20 RPS> <duration>" >&2
  exit 64
}

scenario="${1:-}"
level="${2:-}"
duration="${3:-}"

[[ "$scenario" =~ ^(hot|mixed|soak)$ ]] || usage
[[ "$level" =~ ^(1|2|5|10|20)$ ]] || usage
[[ -n "$duration" ]] || usage

: "${AIRS_LOADTEST_EMAIL:?AIRS_LOADTEST_EMAIL 환경변수가 필요합니다.}"
: "${AIRS_LOADTEST_PASSWORD:?AIRS_LOADTEST_PASSWORD 환경변수가 필요합니다.}"

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
result_root="$script_dir/results"
node_id="${AIRS_LOADTEST_NODE_ID:-node_01}"
analytics_date="${AIRS_ANALYTICS_DATE:-$(TZ=Asia/Seoul date +%F)}"
base_url="${AIRS_BASE_URL:-https://airs.bibnear.cloud}"
: "${AIRS_RPI_SSH_TARGET:?AIRS_RPI_SSH_TARGET 환경변수가 필요합니다.}"
: "${AIRS_RPI_SSH_PORT:?AIRS_RPI_SSH_PORT 환경변수가 필요합니다.}"
: "${AIRS_RPI_SSH_KEY:?AIRS_RPI_SSH_KEY 환경변수가 필요합니다.}"
: "${AIRS_RPI_COMPOSE_DIR:?AIRS_RPI_COMPOSE_DIR 환경변수가 필요합니다.}"
ssh_target="$AIRS_RPI_SSH_TARGET"
ssh_port="$AIRS_RPI_SSH_PORT"
ssh_key="$AIRS_RPI_SSH_KEY"
remote_compose_dir="$AIRS_RPI_COMPOSE_DIR"
mixed_include_co2_trend="${AIRS_MIXED_INCLUDE_CO2_TREND:-true}"

[[ "$base_url" == "https://airs.bibnear.cloud" ]] || {
  echo "Raspberry Pi P0는 공개 HTTPS 주소만 허용합니다." >&2
  exit 64
}
[[ -f "$ssh_key" ]] || {
  echo "Raspberry Pi SSH key를 찾을 수 없습니다." >&2
  exit 66
}

run_stamp="$(TZ=Asia/Seoul date +%Y%m%d-%H%M%S)"
experiment_id="p0-pi-${scenario}-${level}-caddy-r1-${run_stamp}"
result_dir="$result_root/$experiment_id"
k6_container="airs-${experiment_id}-k6"
mkdir -p "$result_dir/server-before" "$result_dir/server-after"

ssh_args=(
  -i "$ssh_key"
  -p "$ssh_port"
  -o BatchMode=yes
  -o ConnectTimeout=10
)

remote() {
  ssh "${ssh_args[@]}" "$ssh_target" "$@"
}

cleanup() {
  touch "$result_dir/.stop-stats" 2>/dev/null || true
  docker stop --time 5 "$k6_container" >/dev/null 2>&1 || true
  if [[ -n "${monitor_pid:-}" ]]; then
    wait "$monitor_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

collect_snapshot() {
  local destination="$1"
  remote "cd '$remote_compose_dir' && docker compose ps --format json" \
    > "$destination/compose-ps.json"
  remote "docker stats --no-stream --format '{{json .}}' airs-spring airs-redis airs-influxdb airs-mysql airs-caddy airs-mosquitto" \
    > "$destination/docker-stats.ndjson"
  remote "cd '$remote_compose_dir' && docker compose exec -T redis redis-cli INFO stats" \
    > "$destination/redis-info-stats.txt"
  remote "cd '$remote_compose_dir' && docker compose exec -T redis redis-cli INFO memory" \
    > "$destination/redis-info-memory.txt"
  remote "curl -fsS http://127.0.0.1:8080/actuator/health" > "$destination/spring-health.json"
  remote "curl -fsS http://127.0.0.1:8086/health" > "$destination/influx-health.json"
}

monitor_runtime() {
  local unhealthy_samples=0
  local captured_at
  local spring_health
  local influx_health
  local mysql_health
  local redis_health
  local evicted_keys

  printf 'captured_at_kst\tspring\tinflux\tmysql\tredis\n' > "$result_dir/health-timeline.tsv"

  while [[ ! -f "$result_dir/.stop-stats" ]]; do
    captured_at="$(TZ=Asia/Seoul date +%Y-%m-%dT%H:%M:%S%z)"
    printf '{"capturedAt":"%s"}\n' "$captured_at"
    remote "docker stats --no-stream --format '{{json .}}' airs-spring airs-redis airs-influxdb airs-mysql airs-caddy airs-mosquitto" \
      || true

    spring_health="$(
      remote "curl -fsS http://127.0.0.1:8080/actuator/health" 2>/dev/null \
        | node -e 'let s="";process.stdin.on("data",c=>s+=c);process.stdin.on("end",()=>{try{process.stdout.write(JSON.parse(s).status||"UNKNOWN")}catch{process.stdout.write("UNKNOWN")}})'
    )"
    read -r influx_health mysql_health redis_health evicted_keys <<< "$(
      remote "cd '$remote_compose_dir' && printf '%s %s %s %s\n' \
        \"\$(curl -fsS http://127.0.0.1:8086/health | sed -n 's/.*\\\"status\\\":\\\"\\([^\\\"]*\\)\\\".*/\\1/p')\" \
        \"\$(docker inspect --format '{{.State.Health.Status}}' airs-mysql)\" \
        \"\$(docker compose exec -T redis redis-cli PING | tr -d '\\r')\" \
        \"\$(docker compose exec -T redis redis-cli INFO stats | sed -n 's/^evicted_keys:\\([0-9]*\\).*/\\1/p' | tr -d '\\r')\""
    )"

    printf '%s\t%s\t%s\t%s\t%s\n' \
      "$captured_at" "$spring_health" "${influx_health:-UNKNOWN}" \
      "${mysql_health:-UNKNOWN}" "${redis_health:-UNKNOWN}" \
      >> "$result_dir/health-timeline.tsv"

    if [[ "$spring_health" != "UP" || "$influx_health" != "pass" || "$mysql_health" != "healthy" || "$redis_health" != "PONG" ]]; then
      unhealthy_samples=$((unhealthy_samples + 1))
    else
      unhealthy_samples=0
    fi

    if [[ "$unhealthy_samples" -ge 2 ]]; then
      printf 'health check failed for two consecutive samples at %s\n' "$captured_at" \
        > "$result_dir/runtime-stop-reason.txt"
      docker stop --time 5 "$k6_container" >/dev/null 2>&1 || true
      break
    fi

    if [[ -n "${evicted_keys:-}" && "$evicted_keys" -gt 0 ]]; then
      printf 'Redis eviction detected at %s\n' "$captured_at" \
        > "$result_dir/runtime-stop-reason.txt"
      docker stop --time 5 "$k6_container" >/dev/null 2>&1 || true
      break
    fi

    sleep 5
  done
}

cat > "$result_dir/manifest.json" <<EOF
{
  "experimentId": "$experiment_id",
  "environment": "Raspberry Pi via public HTTPS",
  "profile": "raspberry",
  "scenario": "$scenario",
  "targetService": "caddy",
  "targetLevel": $level,
  "duration": "$duration",
  "nodeId": "$node_id",
  "analyticsDate": "$analytics_date",
  "mixedIncludeCo2Trend": $mixed_include_co2_trend,
  "startedAtKst": "$(TZ=Asia/Seoul date +%Y-%m-%dT%H:%M:%S%z)"
}
EOF

collect_snapshot "$result_dir/server-before"
started_at_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
monitor_runtime > "$result_dir/docker-stats-timeline.ndjson" 2>&1 &
monitor_pid=$!

k6_script="scenarios/mixed-read.js"
k6_arguments=(
  -e "AIRS_TARGET_RPS=$level"
  -e "AIRS_TEST_DURATION=$duration"
)
if [[ "$scenario" == "hot" ]]; then
  k6_script="scenarios/hot-cache.js"
  k6_arguments+=(-e "AIRS_LOADTEST_ENDPOINT=sensor-temperature-1mo")
else
  k6_arguments+=(-e "AIRS_INCLUDE_ALERT_DASHBOARD=true")
fi

set +e
docker run --rm -i \
  --name "$k6_container" \
  -e "AIRS_LOADTEST_EMAIL=$AIRS_LOADTEST_EMAIL" \
  -e "AIRS_LOADTEST_PASSWORD=$AIRS_LOADTEST_PASSWORD" \
  -e "AIRS_ANALYTICS_DATE=$analytics_date" \
  -e "AIRS_MIXED_INCLUDE_CO2_TREND=$mixed_include_co2_trend" \
  -e "AIRS_LOADTEST_PROFILE=raspberry" \
  -e "AIRS_BASE_URL=$base_url" \
  -e "AIRS_LOADTEST_NODE_ID=$node_id" \
  -e "AIRS_EXPERIMENT_ID=$experiment_id" \
  -e "AIRS_SUMMARY_PATH=/results/summary.json" \
  "${k6_arguments[@]}" \
  -v "$script_dir:/scripts:ro" \
  -v "$result_dir:/results" \
  grafana/k6:0.54.0 \
  run --include-system-env-vars --out json=/results/k6-raw.ndjson "/scripts/$k6_script" \
  2>&1 | tee "$result_dir/k6-console.log"
k6_exit="${PIPESTATUS[0]}"
set -e

touch "$result_dir/.stop-stats"
wait "$monitor_pid" 2>/dev/null || true
monitor_pid=""

if [[ -s "$result_dir/k6-raw.ndjson" ]]; then
  gzip -f "$result_dir/k6-raw.ndjson"
fi

collect_snapshot "$result_dir/server-after"
remote "cd '$remote_compose_dir' && docker compose logs --since '$started_at_utc' spring caddy redis influxdb mysql mosquitto" \
  > "$result_dir/service-logs.txt" 2>&1
node "$script_dir/analyze-p0-result.mjs" "$result_dir" > "$result_dir/analysis-console.json"

summary_values="$(
  node -e '
    const fs=require("fs");
    const p=process.argv[1];
    if(!fs.existsSync(p)){process.stdout.write("1\t999999\t0");process.exit(0)}
    const d=JSON.parse(fs.readFileSync(p,"utf8"));
    const m=d.k6?.metrics||{};
    process.stdout.write(`${m.http_req_failed?.values?.rate??1}\t${m.http_req_duration?.values?.["p(95)"]??999999}\t${m.dropped_iterations?.values?.count??0}`);
  ' "$result_dir/summary.json"
)"
IFS=$'\t' read -r failed_rate p95_ms dropped <<< "$summary_values"
error_lines="$(grep -Eic '(^|[^A-Z])(ERROR|OutOfMemoryError|timeout)([^A-Z]|$)' "$result_dir/service-logs.txt" || true)"
evictions_before="$(awk -F: '/^evicted_keys:/{gsub(/\r/,"",$2); print $2}' "$result_dir/server-before/redis-info-stats.txt")"
evictions_after="$(awk -F: '/^evicted_keys:/{gsub(/\r/,"",$2); print $2}' "$result_dir/server-after/redis-info-stats.txt")"
stop_reason=""

if [[ ! -s "$result_dir/summary.json" ]]; then
  stop_reason="sanitized k6 summary was not created"
elif [[ -s "$result_dir/runtime-stop-reason.txt" ]]; then
  stop_reason="$(cat "$result_dir/runtime-stop-reason.txt")"
elif [[ "$k6_exit" -ne 0 ]]; then
  stop_reason="k6 exit code $k6_exit"
elif awk "BEGIN {exit !($failed_rate >= 0.01)}"; then
  stop_reason="HTTP failure rate >= 1%"
elif awk "BEGIN {exit !($p95_ms >= 1000)}"; then
  stop_reason="P95 >= 1000ms"
elif [[ "$dropped" -gt 0 ]]; then
  stop_reason="dropped iterations > 0"
elif [[ "$evictions_after" -gt "$evictions_before" ]]; then
  stop_reason="Redis eviction increased"
elif [[ "$error_lines" -gt 0 ]]; then
  stop_reason="service error or timeout log detected"
fi

if grep -R -F -q -- "$AIRS_LOADTEST_PASSWORD" "$result_dir"; then
  printf 'STOP\ncredential value detected in result artifacts\n' > "$result_dir/stage-decision.txt"
  echo "STOP: credential value detected in result artifacts"
  exit 2
fi

if [[ -n "$stop_reason" ]]; then
  printf 'STOP\n%s\n' "$stop_reason" > "$result_dir/stage-decision.txt"
  echo "STOP: $stop_reason"
  exit 2
fi

printf 'PASS\n' > "$result_dir/stage-decision.txt"
echo "PASS: $experiment_id"
