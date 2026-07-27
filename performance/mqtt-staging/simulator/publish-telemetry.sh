#!/bin/sh
# 운영 broker와 무관한 로컬 fixture 노드 telemetry를 일정한 간격으로 발행합니다.
set -eu

node_count="${SIMULATOR_NODE_COUNT:-10}"
interval_seconds="${SIMULATOR_INTERVAL_SECONDS:-5}"
duration_seconds="${SIMULATOR_DURATION_SECONDS:-120}"
co2_mode="${SIMULATOR_CO2_MODE:-steady}"
mqtt_host="${MQTT_HOST:-mosquitto}"
mqtt_port="${MQTT_PORT:-1883}"
mqtt_qos="${MQTT_QOS:-0}"

if [ "$node_count" -lt 1 ] || [ "$node_count" -gt 100 ]; then
  echo "SIMULATOR_NODE_COUNT는 1~100 범위여야 합니다." >&2
  exit 1
fi

if [ "$interval_seconds" -lt 1 ] || [ "$duration_seconds" -lt "$interval_seconds" ]; then
  echo "SIMULATOR_INTERVAL_SECONDS와 SIMULATOR_DURATION_SECONDS 설정이 올바르지 않습니다." >&2
  exit 1
fi

case "$co2_mode" in
  steady|rapid-rise)
    ;;
  *)
    echo "SIMULATOR_CO2_MODE는 steady 또는 rapid-rise여야 합니다." >&2
    exit 1
    ;;
esac

iteration=0
total_iterations=$((duration_seconds / interval_seconds))

echo "로컬 MQTT 부하 실험 시작: nodes=${node_count}, interval=${interval_seconds}s, duration=${duration_seconds}s, mode=${co2_mode}"

while [ "$iteration" -lt "$total_iterations" ]; do
  node_index=1

  while [ "$node_index" -le "$node_count" ]; do
    node_suffix=$(printf '%03d' "$node_index")
    node_id="stage_node_${node_suffix}"
    temperature="$((23 + (node_index % 4))).$((iteration % 10))"
    humidity="$((45 + ((node_index + iteration) % 20))).$((iteration % 10))"

    if [ "$co2_mode" = "rapid-rise" ]; then
      # 5초 간격에서 10분 동안 약 120ppm 상승해 CO2 변화량 정책을 검증할 수 있게 한다.
      co2_ppm=$((820 + iteration + (node_index % 5)))
    else
      co2_ppm=$((650 + ((node_index * 7 + iteration * 3) % 140)))
    fi

    pir_detected=0
    mmwave_detected=0
    if [ $(((node_index + iteration) % 12)) -eq 0 ]; then
      pir_detected=1
    fi
    if [ $(((node_index + iteration) % 20)) -eq 0 ]; then
      mmwave_detected=1
    fi

    wifi_signal_dbm=$((-55 - ((node_index + iteration) % 20)))
    payload=$(printf '{"node_id":"%s","temperature_c":%s,"humidity_pct":%s,"co2_ppm":%s,"scd41_temperature_c":%s,"scd41_humidity_pct":%s,"pir_detected":%s,"mmwave_detected":%s,"wifi_signal_dbm":%s,"sensor_status":{"dht22":"OK","scd41":"OK"}}' \
      "$node_id" "$temperature" "$humidity" "$co2_ppm" "$temperature" "$humidity" "$pir_detected" "$mmwave_detected" "$wifi_signal_dbm")

    mosquitto_pub -h "$mqtt_host" -p "$mqtt_port" -q "$mqtt_qos" -t "airs/node/${node_id}/telemetry" -m "$payload" &
    node_index=$((node_index + 1))
  done

  wait
  iteration=$((iteration + 1))
  echo "발행 완료: iteration=${iteration}/${total_iterations}, messages=$((iteration * node_count))"

  if [ "$iteration" -lt "$total_iterations" ]; then
    sleep "$interval_seconds"
  fi
done

echo "로컬 MQTT 부하 실험 완료: total_messages=$((total_iterations * node_count))"
