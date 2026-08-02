#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 cold <VU> [repeat] | hot <RPS> [duration] [backend|caddy] | mixed <RPS> [duration] [backend|caddy] | soak <RPS> [duration] [backend|caddy]" >&2
  exit 64
}

scenario="${1:-}"
level="${2:-}"
detail="${3:-}"
target_service="${4:-backend}"

[[ -n "$scenario" && -n "$level" ]] || usage

case "$scenario" in
  cold)
    [[ "$level" =~ ^(20|50|100|200|500)$ ]] || usage
    repeat="${detail:-1}"
    [[ "$repeat" =~ ^[1-3]$ ]] || usage
    duration=""
    target_service="backend"
    ;;
  hot|mixed|soak)
    if [[ "$scenario" == "hot" ]]; then
      [[ "$level" =~ ^(20|50|100|200|300|500|750|1000)$ ]] || usage
    else
      [[ "$level" =~ ^(1|2|5|10|20|30|40|50|100|200|300|500|750|1000)$ ]] || usage
    fi
    repeat="1"
    duration="${detail:-30s}"
    [[ "$target_service" == "backend" || "$target_service" == "caddy" ]] || usage
    ;;
  *)
    usage
    ;;
esac

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
backend_dir="$(CDPATH= cd -- "$script_dir/../.." && pwd)"
compose_file="$backend_dir/performance/mqtt-staging/docker-compose.yml"
result_root="$script_dir/results"
node_id="${AIRS_LOADTEST_NODE_ID:-stage_node_0001}"
metric="${AIRS_STAMPEDE_METRIC:-temperature}"
period="${AIRS_STAMPEDE_PERIOD:-1mo}"
analytics_date="${AIRS_ANALYTICS_DATE:-$(TZ=Asia/Seoul date +%F)}"
mixed_include_co2_trend="${AIRS_MIXED_INCLUDE_CO2_TREND:-true}"
stage_email="${AIRS_LOADTEST_EMAIL:-stage-admin@example.invalid}"
stage_password="${AIRS_LOADTEST_PASSWORD:-1234}"
stage_cache_ttl_seconds="${AIRS_STAGE_CACHE_TTL_SECONDS:-3600}"
network_name="airs-perf-staging_default"

[[ "$mixed_include_co2_trend" == "true" || "$mixed_include_co2_trend" == "false" ]] || {
  echo "AIRS_MIXED_INCLUDE_CO2_TREND는 true 또는 false여야 합니다." >&2
  exit 64
}
[[ "$stage_cache_ttl_seconds" =~ ^[1-9][0-9]*$ ]] || {
  echo "AIRS_STAGE_CACHE_TTL_SECONDS는 양의 정수여야 합니다." >&2
  exit 64
}

if [[ "$target_service" == "caddy" ]]; then
  base_url="http://caddy:8080"
else
  base_url="http://backend:8080"
fi

run_stamp="$(TZ=Asia/Seoul date +%Y%m%d-%H%M%S)"
experiment_id="p0-${scenario}-${level}-${target_service}-r${repeat}-${run_stamp}"
result_dir="$result_root/$experiment_id"
mkdir -p "$result_dir/server-before" "$result_dir/server-after"
k6_container="airs-${experiment_id}-k6"

compose() {
  docker compose -f "$compose_file" "$@"
}

cleanup_monitor() {
  touch "$result_dir/.stop-stats" 2>/dev/null || true
  if [[ -n "${stats_pid:-}" ]]; then
    wait "$stats_pid" 2>/dev/null || true
  fi
}
trap cleanup_monitor EXIT INT TERM

container_ids() {
  compose ps -q
}

collect_runtime_snapshot() {
  local destination="$1"
  compose ps --format json > "$destination/compose-ps.json"
  docker stats --no-stream --format '{{json .}}' $(container_ids) > "$destination/docker-stats.ndjson"
  compose exec -T redis redis-cli INFO stats > "$destination/redis-info-stats.txt"
  compose exec -T redis redis-cli INFO memory > "$destination/redis-info-memory.txt"
  curl -fsS http://127.0.0.1:18080/actuator/health > "$destination/spring-health.json"
  curl -fsS http://127.0.0.1:18086/health > "$destination/influx-health.json"
}

login_observer() {
  local payload
  payload="$(
    AIRS_OBSERVER_EMAIL="$stage_email" AIRS_OBSERVER_PASSWORD="$stage_password" \
      node -e 'process.stdout.write(JSON.stringify({email:process.env.AIRS_OBSERVER_EMAIL,password:process.env.AIRS_OBSERVER_PASSWORD}))'
  )"
  curl -fsS \
    -H 'Content-Type: application/json' \
    -d "$payload" \
    http://127.0.0.1:18080/airs/auth/login \
    | node -e 'let input="";process.stdin.on("data",chunk=>input+=chunk);process.stdin.on("end",()=>{const token=JSON.parse(input).accessToken;if(!token)process.exit(1);process.stdout.write(token);})'
}

collect_metric_snapshot() {
  local destination="$1"
  local observer_token="$2"

  collect_metric_pair "$destination" "$observer_token" "$metric" "$period" "primary"

  if [[ "$scenario" == "mixed" || "$scenario" == "soak" ]]; then
    collect_metric_pair "$destination" "$observer_token" "co2" "1d" "co2-1d"
  fi
}

collect_metric_pair() {
  local destination="$1"
  local observer_token="$2"
  local snapshot_metric="$3"
  local snapshot_period="$4"
  local snapshot_prefix="$5"
  local response
  local source
  local encoded_metric="airs.node.sensor.trend.request"

  for source in hit miss hit_after_wait stale_hit miss_timeout_fallback; do
    response="$(curl -sS \
      -H "Authorization: Bearer $observer_token" \
      --get "http://127.0.0.1:18080/actuator/metrics/$encoded_metric" \
      --data-urlencode "tag=metric:$snapshot_metric" \
      --data-urlencode "tag=period:$snapshot_period" \
      --data-urlencode "tag=source:$source" \
      --data-urlencode "tag=outcome:success")"
    if [[ -z "$response" ]]; then
      response='{}'
    fi
    printf '%s\n' "$response" > "$destination/request-$snapshot_prefix-$source.json"
  done

  response="$(curl -sS \
    -H "Authorization: Bearer $observer_token" \
    --get 'http://127.0.0.1:18080/actuator/metrics/airs.node.sensor.trend.influx.load' \
    --data-urlencode "tag=metric:$snapshot_metric" \
    --data-urlencode "tag=period:$snapshot_period" \
    --data-urlencode 'tag=outcome:success')"
  if [[ -z "$response" ]]; then
    response='{}'
  fi
  printf '%s\n' "$response" > "$destination/influx-load-$snapshot_prefix.json"
}

monitor_stats() {
  local observer_token="$1"
  local captured_at
  local spring_health
  local influx_health
  local mysql_health
  local redis_health
  local heap_used
  local heap_committed
  local unhealthy_samples=0
  local evicted_keys

  printf 'captured_at_kst\tspring\tinflux\tmysql\tredis\n' > "$result_dir/health-timeline.tsv"
  printf 'captured_at_kst\theap_used_bytes\theap_committed_bytes\n' > "$result_dir/jvm-memory-timeline.tsv"

  while [[ ! -f "$result_dir/.stop-stats" ]]; do
    captured_at="$(TZ=Asia/Seoul date +%Y-%m-%dT%H:%M:%S%z)"
    printf '{"capturedAt":"%s"}\n' "$captured_at"
    docker stats --no-stream --format '{{json .}}' $(container_ids)

    spring_health="$(
      curl -fsS http://127.0.0.1:18080/actuator/health 2>/dev/null \
        | node -e 'let s="";process.stdin.on("data",c=>s+=c);process.stdin.on("end",()=>{try{process.stdout.write(JSON.parse(s).status||"UNKNOWN")}catch{process.stdout.write("UNKNOWN")}})'
    )"
    influx_health="$(
      curl -fsS http://127.0.0.1:18086/health 2>/dev/null \
        | node -e 'let s="";process.stdin.on("data",c=>s+=c);process.stdin.on("end",()=>{try{process.stdout.write(JSON.parse(s).status||"UNKNOWN")}catch{process.stdout.write("UNKNOWN")}})'
    )"
    mysql_health="$(
      docker inspect --format '{{.State.Health.Status}}' airs-perf-staging-mysql-1 2>/dev/null \
        || printf 'UNKNOWN'
    )"
    redis_health="$(
      compose exec -T redis redis-cli PING 2>/dev/null \
        || printf 'UNKNOWN'
    )"
    printf '%s\t%s\t%s\t%s\t%s\n' \
      "$captured_at" "$spring_health" "$influx_health" "$mysql_health" "$redis_health" \
      >> "$result_dir/health-timeline.tsv"

    if [[ "$spring_health" != "UP" || "$influx_health" != "pass" || "$mysql_health" != "healthy" || "$redis_health" != "PONG" ]]; then
      unhealthy_samples=$((unhealthy_samples + 1))
    else
      unhealthy_samples=0
    fi

    evicted_keys="$(
      compose exec -T redis redis-cli INFO stats 2>/dev/null \
        | awk -F: '/^evicted_keys:/{gsub(/\r/,"",$2); print $2}'
    )"

    if [[ "$unhealthy_samples" -ge 2 ]]; then
      printf 'health check failed for two consecutive samples at %s\n' "$captured_at" \
        > "$result_dir/runtime-stop-reason.txt"
      docker stop --time 5 "$k6_container" >/dev/null 2>&1 || true
      break
    fi

    if [[ -n "$evicted_keys" && "$evicted_keys" -gt 0 ]]; then
      printf 'Redis eviction detected at %s\n' "$captured_at" \
        > "$result_dir/runtime-stop-reason.txt"
      docker stop --time 5 "$k6_container" >/dev/null 2>&1 || true
      break
    fi

    heap_used="$(
      curl -fsS -H "Authorization: Bearer $observer_token" \
        --get 'http://127.0.0.1:18080/actuator/metrics/jvm.memory.used' \
        --data-urlencode 'tag=area:heap' 2>/dev/null \
        | node -e 'let s="";process.stdin.on("data",c=>s+=c);process.stdin.on("end",()=>{try{process.stdout.write(String(JSON.parse(s).measurements?.[0]?.value??""))}catch{}})'
    )"
    heap_committed="$(
      curl -fsS -H "Authorization: Bearer $observer_token" \
        --get 'http://127.0.0.1:18080/actuator/metrics/jvm.memory.committed' \
        --data-urlencode 'tag=area:heap' 2>/dev/null \
        | node -e 'let s="";process.stdin.on("data",c=>s+=c);process.stdin.on("end",()=>{try{process.stdout.write(String(JSON.parse(s).measurements?.[0]?.value??""))}catch{}})'
    )"
    printf '%s\t%s\t%s\n' "$captured_at" "$heap_used" "$heap_committed" \
      >> "$result_dir/jvm-memory-timeline.tsv"

    sleep 5
  done
}

cat > "$result_dir/manifest.json" <<EOF
{
  "experimentId": "$experiment_id",
  "environment": "Mac Docker staging",
  "profile": "staging",
  "scenario": "$scenario",
  "targetService": "$target_service",
  "targetLevel": $level,
  "repeat": $repeat,
  "duration": "${duration:-one iteration per VU}",
  "nodeId": "$node_id",
  "metric": "$metric",
  "period": "$period",
  "analyticsDate": "$analytics_date",
  "mixedIncludeCo2Trend": $mixed_include_co2_trend,
  "cacheTtlSeconds": $stage_cache_ttl_seconds,
  "aiEvaluationSchedulerEnabled": false,
  "startedAtKst": "$(TZ=Asia/Seoul date +%Y-%m-%dT%H:%M:%S%z)"
}
EOF

collect_runtime_snapshot "$result_dir/server-before"
observer_token="$(login_observer)"
collect_metric_snapshot "$result_dir/server-before" "$observer_token"

cache_key="airs:stage:node:sensor-trend:v1:node:${node_id}:metric:${metric}:period:${period}"
if [[ "$scenario" == "cold" || "$scenario" == "hot" ]]; then
  compose exec -T redis redis-cli DEL "$cache_key" > "$result_dir/target-cache-delete.txt"
fi

monitor_stats "$observer_token" > "$result_dir/docker-stats-timeline.ndjson" 2>&1 &
stats_pid=$!
unset observer_token
started_at_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

k6_script="scenarios/mixed-read.js"
k6_arguments=()

case "$scenario" in
  cold)
    k6_script="scenarios/cache-stampede.js"
    k6_arguments+=(
      -e "AIRS_STAMPEDE_VUS=$level"
      -e "AIRS_BARRIER_DELAY_MS=5000"
      -e "AIRS_STAMPEDE_METRIC=$metric"
      -e "AIRS_STAMPEDE_PERIOD=$period"
    )
    ;;
  hot)
    k6_script="scenarios/hot-cache.js"
    k6_arguments+=(
      -e "AIRS_TARGET_RPS=$level"
      -e "AIRS_TEST_DURATION=$duration"
      -e "AIRS_LOADTEST_ENDPOINT=sensor-temperature-1mo"
    )
    ;;
  mixed|soak)
    k6_script="scenarios/mixed-read.js"
    k6_arguments+=(
      -e "AIRS_TARGET_RPS=$level"
      -e "AIRS_TEST_DURATION=$duration"
      -e "AIRS_INCLUDE_ALERT_DASHBOARD=true"
    )
    ;;
esac

set +e
docker run --rm -i \
  --name "$k6_container" \
  --network "$network_name" \
  -e "AIRS_LOADTEST_EMAIL=$stage_email" \
  -e "AIRS_LOADTEST_PASSWORD=$stage_password" \
  -e "AIRS_ANALYTICS_DATE=$analytics_date" \
  -e "AIRS_MIXED_INCLUDE_CO2_TREND=$mixed_include_co2_trend" \
  -e "AIRS_LOADTEST_PROFILE=staging" \
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

sleep 6
touch "$result_dir/.stop-stats"
wait "$stats_pid" 2>/dev/null || true
stats_pid=""
if [[ -s "$result_dir/k6-raw.ndjson" ]]; then
  gzip -f "$result_dir/k6-raw.ndjson"
fi

observer_token="$(login_observer)"
collect_metric_snapshot "$result_dir/server-after" "$observer_token"
unset observer_token
collect_runtime_snapshot "$result_dir/server-after"
compose logs --since "$started_at_utc" backend caddy redis influxdb mysql mosquitto > "$result_dir/service-logs.txt" 2>&1
node "$script_dir/analyze-p0-result.mjs" "$result_dir" > "$result_dir/analysis-console.json"

if [[ -s "$result_dir/summary.json" ]]; then
  summary_values="$(
    node -e '
      const document=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));
      const metrics=document.k6?.metrics||{};
      const failed=metrics.http_req_failed?.values?.rate ?? 1;
      const p95=metrics.http_req_duration?.values?.["p(95)"] ?? 999999;
      const dropped=metrics.dropped_iterations?.values?.count ?? 0;
      process.stdout.write(`${failed}\t${p95}\t${dropped}`);
    ' "$result_dir/summary.json"
  )"
  IFS=$'\t' read -r failed_rate p95_ms dropped <<< "$summary_values"
else
  failed_rate="1"
  p95_ms="999999"
  dropped="0"
fi
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

if grep -R -F -q -- "$stage_password" "$result_dir"; then
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
