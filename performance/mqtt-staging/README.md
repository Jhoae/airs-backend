# AIRS 로컬 MQTT 부하 실험 환경

이 환경은 운영 라즈베리파이와 완전히 분리된 다수 센서 telemetry 검증용 Compose입니다.

- Compose 프로젝트명: `airs-perf-staging`
- MySQL: `127.0.0.1:13306`
- InfluxDB: `127.0.0.1:18086`
- Mosquitto: `127.0.0.1:11883`
- Redis: `127.0.0.1:16379`
- Spring API 1: `127.0.0.1:18080`
- Spring API 2: `127.0.0.1:18081` (`multi-backend` profile에서만 기동)
- 모든 포트는 Mac loopback에만 열리므로 외부·라즈베리파이·운영 Docker network에 노출되지 않습니다.

## 기동과 정리

```bash
docker compose -f performance/mqtt-staging/docker-compose.yml up -d --build
docker compose -f performance/mqtt-staging/docker-compose.yml ps
```

`seed`는 Flyway로 스키마가 준비된 뒤 `stage_node_0001`부터 `stage_node_1000`까지의 노드와 설치 공간을 한 번 준비합니다. 기본 기동만으로 telemetry는 발행하지 않습니다.

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

100개 노드가 실제 telemetry 주기와 같은 5초마다 30분 동안 QoS 1 telemetry를 발행하는 지속 수집 실험입니다. 기대 논리 telemetry 수는 `100 x 360 = 36,000`이며, 이는 Spring subscriber가 하나의 wildcard topic으로 초당 평균 3.3개 메시지를 지속 처리하는 조건입니다. 이 실험은 아직 실행 전이므로, 결과를 포트폴리오 수치로 사용하지 않습니다.

```bash
SIMULATOR_NODE_COUNT=100 SIMULATOR_INTERVAL_SECONDS=5 SIMULATOR_DURATION_SECONDS=1800 MQTT_QOS=1 \
docker compose -f performance/mqtt-staging/docker-compose.yml --profile simulator run --rm simulator
./performance/mqtt-staging/verify-stage.sh
```

1,000개 fixture topic을 실제 telemetry 주기와 같은 5초 주기로 발행하면, 평균 초당 200건이면서 5초마다 1,000건이 몰리는 버스트를 재현할 수 있습니다. Node 기반 simulator는 MQTT 연결을 유지한 채 topic별 payload를 발행합니다. 기본값은 연결 하나가 여러 가상 노드를 맡지만, `SIMULATOR_CLIENT_COUNT`로 실제 연결 수까지 따로 늘릴 수 있습니다. 물리 Wi-Fi·CPU 제약은 재현하지 않으므로, 이 결과는 Spring·broker의 publish 처리량과 중복 처리 회귀를 검증한 결과로만 기록합니다.

```bash
SIMULATOR_NODE_COUNT=1000 SIMULATOR_CLIENT_COUNT=50 SIMULATOR_INTERVAL_SECONDS=5 SIMULATOR_DURATION_SECONDS=120 MQTT_QOS=1 \
docker compose -f performance/mqtt-staging/docker-compose.yml --profile simulator run --rm simulator
```

## 2026-07-28 실측 버스트 검증

처음에는 MQTT callback 안에서 MySQL snapshot 갱신, 재실 판정, InfluxDB 적재까지 직렬로 실행했다. `200개 노드 x 1초 x 120초 = 24,000건` QoS 1 발행에서 InfluxDB `co2_ppm` 적재는 10,141건에 그쳤고, Mosquitto 로그에 subscriber의 outgoing message drop이 남았다. Influx 비동기 batch write만으로는 callback 병목을 없앨 수 없었다.

따라서 `TelemetryIngestionDispatcher`를 추가했다. MQTT callback은 JSON을 해석한 뒤 `nodeId` 해시로 선택한 8개 단일 worker 중 하나의 bounded queue에 넣고 즉시 반환한다. 같은 node는 항상 같은 worker를 사용하므로 순서는 보존하고, 서로 다른 node는 병렬로 처리한다. queue가 가득 차면 callback이 대기해 메모리에서 임의로 버리지 않고 broker까지 backpressure를 전파한다.

수정 뒤 아래 조건으로 다시 검증했다.

```text
실행 위치: 개발 Mac의 Docker Desktop, 운영과 분리된 airs-perf-staging
가상 노드: 1,000개
MQTT 연결: 50개 지속 연결
QoS: 1
발행 주기: 노드별 5초
실행 시간: 120초
예상 telemetry: 1,000 x 24 = 24,000건
```

결과는 다음과 같다.

```text
simulator published_messages: 24,000
InfluxDB co2_ppm 전체 count: 24,000
노드별 co2_ppm count 최솟값: 24
노드별 co2_ppm count 최댓값: 24
MySQL node_status_snapshots / space_status_snapshots: 1,000 / 1,000행
Mosquitto dropped/error/denied 로그: 없음
```

이는 1,000개의 가상 node identity가 5초 버스트로 보낸 QoS 1 telemetry를 현재 Spring 수집 경로가 이 조건에서 누락 없이 InfluxDB까지 전달했다는 증거다. Raspberry Pi의 CPU·Wi-Fi·실제 펌웨어 네트워크, 30분 이상 지속 수집, InfluxDB 장기 지연, 다중 Spring subscriber는 아직 이 실험으로 증명하지 않는다.

## QoS 1 중복·순서 역전 검증

새 telemetry 계약의 `boot_id`와 `sequence_no`가 있을 때만 Spring은 노드·부팅 세션별 최대 순번을 Redis에서 원자적으로 비교합니다. `duplicate`는 같은 순번을 두 번 발행하고, `out-of-order`는 최신 순번 뒤에 직전 순번을 한 번 더 발행합니다. 두 경우 모두 중복·과거 메시지는 재실 계산과 MySQL·InfluxDB 적재 전에 건너뜁니다.

```bash
SIMULATOR_NODE_COUNT=10 SIMULATOR_INTERVAL_SECONDS=1 SIMULATOR_DURATION_SECONDS=30 MQTT_QOS=1 SIMULATOR_SEQUENCE_MODE=duplicate \
docker compose -f performance/mqtt-staging/docker-compose.yml --profile simulator run --rm simulator

SIMULATOR_NODE_COUNT=10 SIMULATOR_INTERVAL_SECONDS=1 SIMULATOR_DURATION_SECONDS=30 MQTT_QOS=1 SIMULATOR_SEQUENCE_MODE=out-of-order \
docker compose -f performance/mqtt-staging/docker-compose.yml --profile simulator run --rm simulator
```

## 다중 Spring 인스턴스 cache 검증

`multi-backend` profile은 MySQL·InfluxDB·Redis를 공유하는 두 번째 Spring을 `18081`에만 추가합니다. 두 번째 인스턴스는 MQTT 수신과 AI 평가 scheduler를 끄므로 같은 telemetry를 중복 적재하거나 alert를 중복 평가하지 않습니다. 이 실험은 shared Redis lock과 cache가 인스턴스 경계를 넘어 동작하는지 확인하는 용도입니다.

```bash
docker compose -f performance/mqtt-staging/docker-compose.yml --profile multi-backend up -d backend-2
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
5. `boot_id`·`sequence_no`가 있는 QoS 1 telemetry에서 중복·순서 역전이 최신 상태를 되돌리지 않는지

이 환경은 실제 운영 처리량을 증명하는 최종 성능 수치용이 아닙니다. 하드웨어·Docker Desktop 자원·로컬 네트워크의 영향을 받으므로, 병목 재현과 회귀 검증을 위한 안전한 실험장으로 사용합니다.
