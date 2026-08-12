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
- 수동 ACK 1,000건 burst가 Mosquitto 기본 per-client queue 1,000건에 먼저 막히지 않도록 staging broker의 `max_queued_messages`만 20,000으로 높였습니다. 운영 Mosquitto 설정을 변경하거나 같은 값을 권장한다는 뜻은 아닙니다.

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

따라서 `TelemetryIngestionDispatcher`를 추가해 `nodeId` 해시로 선택한 8개 단일 worker와 bounded queue로 저장 작업을 분리했다. 이후 ACK·DB 영속화 사이의 유실 경계를 보완하면서 callback은 worker 전달 뒤 ACK를 보류하고, worker의 MySQL transaction이 commit된 뒤에만 수동 ACK한다. 같은 node는 같은 worker를 사용하고 MySQL의 node별 ingestion state row를 잠가 순서를 유지한다. queue가 가득 차면 callback이 대기해 임의 drop 대신 broker 방향으로 backpressure를 전달한다.

MySQL transaction은 sequence·occupancy 상태, 최신 snapshot과 InfluxDB용 outbox를 함께 확정한다. InfluxDB 전달은 별도 publisher가 blocking batch write로 성공을 확인한 뒤 outbox를 완료 처리하며, 실패한 row는 DB 상태로 재시도한다. outbox scheduler가 snapshot이나 occupancy를 다시 계산하지는 않는다.

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
MySQL telemetry_outbox COMPLETED: 24,000
InfluxDB co2_ppm 전체 count: 24,000
노드별 co2_ppm count 최솟값: 24
노드별 co2_ppm count 최댓값: 24
MySQL node_status_snapshots / space_status_snapshots: 1,000 / 1,000행
telemetry_ingestion_states: 1,000행, 모든 last_sequence_no=24
Mosquitto dropped/error/denied 로그: 없음
```

이는 1,000개의 가상 node identity가 5초 주기로 보낸 QoS 1 telemetry 24,000건을 현재 Spring 수집 경로가 MySQL transaction과 outbox를 거쳐 InfluxDB까지 전달했다는 증거다. 이 staging의 callback 종료·MySQL 중단·InfluxDB 중단 실험에서는 미확인 QoS 재전달과 재시작 후 outbox 복구도 확인했다. Raspberry Pi의 CPU·Wi-Fi·실제 펌웨어 QoS, broker 재시작 뒤 session persistence, 장기 지속 수집과 다중 Spring subscriber는 증명하지 않는다.

## 2026-08-09 striped worker 수 비교

8개를 임의의 상수로 두지 않기 위해 동일한 24,000건 조건에서 worker를 `1·2·4·8·16`개로 바꿔 한 차례씩 비교했다. 모든 조건에서 sequence gap·broker drop·DEAD 없이 24,000건이 MySQL과 InfluxDB에 반영됐다.

| worker | 수신→outbox 생성 P95 | stripe 최대 queue | 발행 종료 뒤 backlog 해소 |
|---:|---:|---:|---:|
| 1 | 252ms | 19건 | 92초 |
| 2 | 422ms | 19건 | 76초 |
| 4 | 351ms | 14건 | 41초 |
| 8 | 136ms | 8건 | 2초 |
| 16 | 93ms | 5건 | 3초 |

worker 16개는 수신 경로의 지연을 더 줄였지만 전체 InfluxDB 전달 완료는 8개보다 빨라지지 않았다. 8개는 worker 4개까지 남아 있던 backlog를 사실상 해소하면서, worker와 전용 queue를 다시 두 배로 늘리기 전의 가장 작은 값이었다. 따라서 현재 staging 부하의 균형점으로 기본값 8을 유지한다. 이는 로컬 Docker 조건의 설정 근거이며 모든 하드웨어의 보편적인 최적값이라는 뜻은 아니다.

## QoS 1 중복·순서 역전 검증

`boot_id`와 `sequence_no`가 있을 때 Spring은 node별 MySQL ingestion state row를 transaction 안에서 잠그고 최대 순번을 비교합니다. `duplicate`는 같은 순번을 두 번 발행하고, `out-of-order`는 최신 순번 뒤에 직전 순번을 한 번 더 발행합니다. 두 경우 모두 snapshot·occupancy·outbox를 변경하지 않습니다. 이 durable state가 처리 완료 기준이며 Redis 응답 cache와 single-flight는 이 경로와 별개입니다.

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
2. 한 telemetry의 sequence·snapshot·outbox가 같은 MySQL transaction으로 확정되고 InfluxDB `airs_stage_raw/sensor_data`까지 전달되는지
3. fixture 노드 수가 늘어도 snapshot row, raw field 행, alert lifecycle이 일관되게 유지되는지
4. 변화량 정책이 절대값만이 아니라 `co2_rate_10m`을 근거로 `CO2_RAPID_RISE` 알림을 생성하는지
5. `boot_id`·`sequence_no`가 있는 QoS 1 telemetry에서 중복·순서 역전이 최신 상태를 되돌리지 않는지
6. MySQL·InfluxDB 중단과 Spring 재시작 뒤 outbox가 `RETRY`에서 `COMPLETED`로 복구되는지

이 환경은 실제 운영 처리량을 증명하는 최종 성능 수치용이 아닙니다. 하드웨어·Docker Desktop 자원·로컬 네트워크의 영향을 받으므로, 병목 재현과 회귀 검증을 위한 안전한 실험장으로 사용합니다.
