# AIRS 로컬 MQTT 부하 실험 환경

이 환경은 운영 라즈베리파이와 완전히 분리된 다수 센서 telemetry 검증용 Compose입니다.

- Compose 프로젝트명: `airs-perf-staging`
- MySQL: `127.0.0.1:13306`
- InfluxDB: `127.0.0.1:18086`
- Mosquitto: `127.0.0.1:11883`
- Redis: `127.0.0.1:16379`
- Spring API: `127.0.0.1:18080`
- 모든 포트는 Mac loopback에만 열리므로 외부·라즈베리파이·운영 Docker network에 노출되지 않습니다.

## 기동과 정리

```bash
docker compose -f performance/mqtt-staging/docker-compose.yml up -d --build
docker compose -f performance/mqtt-staging/docker-compose.yml ps
```

`seed`는 Flyway로 스키마가 준비된 뒤 `stage_node_001`부터 `stage_node_100`까지의 노드와 설치 공간을 한 번 준비합니다. 기본 기동만으로 telemetry는 발행하지 않습니다.

실험이 끝나면 컨테이너와 staging 데이터만 제거합니다.

```bash
docker compose -f performance/mqtt-staging/docker-compose.yml down -v
```

## 다수 센서 telemetry 실험

10개 노드가 5초마다 2분 동안 발행하는 기본 실험입니다.

```bash
docker compose -f performance/mqtt-staging/docker-compose.yml --profile simulator run --rm simulator
```

100개 노드가 1초마다 2분 동안 발행하는 수집량 실험입니다. 이는 초당 약 100개 메시지를 만드는 의도적인 고부하 조건이며 운영 broker에는 전혀 연결하지 않습니다.

```bash
SIMULATOR_NODE_COUNT=100 SIMULATOR_INTERVAL_SECONDS=1 SIMULATOR_DURATION_SECONDS=120 \
docker compose -f performance/mqtt-staging/docker-compose.yml --profile simulator run --rm simulator
```

100개 노드가 1초마다 10분 동안 steady telemetry를 발행하는 지속 수집 실험입니다. 기대 추가량은 `100 x 600 = 60,000` telemetry이며, 완료 뒤 검증 스크립트에서 `co2_ppm`, `temperature_c`, `humidity_pct` field 행 증가량과 100개 최신 snapshot을 함께 확인합니다.

```bash
SIMULATOR_NODE_COUNT=100 SIMULATOR_INTERVAL_SECONDS=1 SIMULATOR_DURATION_SECONDS=600 \
docker compose -f performance/mqtt-staging/docker-compose.yml --profile simulator run --rm simulator
./performance/mqtt-staging/verify-stage.sh
```

## CO2 변화량 알림 lifecycle 실험

`rapid-rise`는 5초마다 약 1ppm씩 올려 10분 동안 약 120ppm을 상승시킵니다. Spring의 10초 평가 스케줄러가 `801~1000ppm` 및 `+100ppm/10분` 조건을 만족한 `CO2_RAPID_RISE` 알림을 생성하는지 확인합니다.

```bash
SIMULATOR_NODE_COUNT=10 SIMULATOR_INTERVAL_SECONDS=5 SIMULATOR_DURATION_SECONDS=660 SIMULATOR_CO2_MODE=rapid-rise \
docker compose -f performance/mqtt-staging/docker-compose.yml --profile simulator run --rm simulator
./performance/mqtt-staging/verify-stage.sh
```

## 검증 대상

1. MQTT topic `airs/node/stage_node_001/telemetry`가 Spring subscriber까지 전달되는지
2. 한 telemetry가 MySQL 최신 snapshot과 InfluxDB `airs_stage_raw/sensor_data`에 모두 적재되는지
3. fixture 노드 수가 늘어도 snapshot row, raw field 행, alert lifecycle이 일관되게 유지되는지
4. 변화량 정책이 절대값만이 아니라 `co2_rate_10m`을 근거로 `CO2_RAPID_RISE` 알림을 생성하는지

이 환경은 실제 운영 처리량을 증명하는 최종 성능 수치용이 아닙니다. 하드웨어·Docker Desktop 자원·로컬 네트워크의 영향을 받으므로, 병목 재현과 회귀 검증을 위한 안전한 실험장으로 사용합니다.
