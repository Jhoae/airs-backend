# AIRS Read-only Performance Test

이 디렉터리는 격리된 Mac Docker staging과 Raspberry Pi의 읽기 API를 재현 가능한 방식으로 측정하기 위한 k6 시나리오다. 테스트 도구는 서비스 `docker-compose.yml`에 포함하지 않으며, 로컬 Mac에서 일회성 Docker 컨테이너로 실행한다.

## 이 테스트가 다루는 범위

- 외부 `https://airs.bibnear.cloud` 경로의 DNS, TLS, Caddy, Spring Security, Redis, InfluxDB, MySQL 권한 조회를 포함한 HTTP 읽기 지연
- Redis cache hit/miss와 raw/rollup Influx 조회가 `sensor-trend` 응답 시간에 주는 영향
- 노드 목록, 알림 대시보드, 노드 추이, 분석 CO2 추이를 혼합했을 때의 HTTP 실패율과 P50/P95/P99

## 이 테스트가 증명하지 않는 범위

- 다수 실제 IoT 기기의 MQTT ingest, Influx cardinality, 센서 펌웨어 부하
- 여러 Spring 인스턴스나 여러 가용 영역의 확장성
- login endpoint의 대량 인증 처리량

`POST /airs/auth/login`은 각 테스트의 `setup()`에서 한 번만 호출한다. MQTT publish와 모든 POST/PATCH/DELETE API는 테스트 대상에서 제외한다.

`GET /airs/admin/alerts/dashboard`는 해당 backend revision이 운영에 배포된 뒤에만 혼합 workload에 포함한다. 기본값은 `AIRS_INCLUDE_ALERT_DASHBOARD=false`이며, API가 200으로 확인된 뒤에만 아래처럼 활성화한다.

```bash
export AIRS_INCLUDE_ALERT_DASHBOARD=true
```

현재 운영에 아직 배포되지 않은 endpoint가 Smoke에서 404가 되는 것은 성능 문제가 아니라 배포 버전 불일치다. 이 경우 endpoint를 몰래 200으로 취급하지 말고, 배포 상태를 별도로 기록한다.

## 사전 준비

1. 운영 telemetry가 정상 수집 중인지 확인한다.
2. 실행 머신에서 Docker Desktop이 실행 중인지 확인한다.
3. 비밀번호를 파일에 저장하지 말고 현재 셸 환경변수로만 넣는다.
4. `AIRS_ANALYTICS_DATE`는 실제 데이터가 있는 KST 날짜를 넣는다.

```bash
cd /path/to/backend

export AIRS_LOADTEST_EMAIL='운영 관리자 이메일'
read -s 'AIRS_LOADTEST_PASSWORD?운영 관리자 비밀번호: '
export AIRS_LOADTEST_PASSWORD
export AIRS_ANALYTICS_DATE='2026-07-27'
export AIRS_RPI_SSH_TARGET='SSH 사용자@호스트'
export AIRS_RPI_SSH_PORT='SSH 포트'
export AIRS_RPI_SSH_KEY='/path/to/private-key'
export AIRS_RPI_COMPOSE_DIR='/remote/path/to/compose'
```

`read -s`는 비밀번호 입력을 화면에 표시하지 않는다. 셸을 닫거나 아래 명령으로 환경변수를 지우면 자격 증명이 남지 않는다.

```bash
unset AIRS_LOADTEST_EMAIL AIRS_LOADTEST_PASSWORD AIRS_ANALYTICS_DATE \
  AIRS_RPI_SSH_TARGET AIRS_RPI_SSH_PORT AIRS_RPI_SSH_KEY AIRS_RPI_COMPOSE_DIR
```

## 실행 명령

모든 명령은 `backend` 디렉터리에서 실행한다. `--env`로 전달한 자격 증명은 컨테이너 환경에만 전달되며 Git 결과물에 기록되지 않는다.

### P0 단계별 실행기

격리된 staging은 한 단계가 `PASS`인 경우에만 다음 단계로 진행한다.

```bash
# 동일 cold key burst: 20 → 50 → 100 → 200 → 500 VU
./performance/k6/run-p0-stage.sh cold 20 1

# hot cache: 20 → 50 → 100 → 200 → 300 → 500 → 750 → 1000 RPS
./performance/k6/run-p0-stage.sh hot 20 30s caddy

# mixed read와 30분 지속 부하
./performance/k6/run-p0-stage.sh mixed 20 30s caddy
./performance/k6/run-p0-stage.sh soak 10 30m caddy
```

Raspberry Pi에서는 staging 결과를 먼저 확인하고 `1 → 2 → 5 → 10 RPS` 순서로만 올린다. 자격 증명은 현재 shell 환경에서만 전달한다.

```bash
./performance/k6/run-p0-raspberry.sh hot 1 30s
./performance/k6/run-p0-raspberry.sh mixed 1 30s
./performance/k6/run-p0-raspberry.sh soak 10 30m
```

두 실행기는 단계별로 별도 결과 디렉터리를 만들고 `summary.json`, 압축한 k6 표본, 서버 전후 snapshot, 자원·health timeline, 서비스 로그와 `stage-decision.txt`를 남긴다. 전체 Redis `FLUSH`는 수행하지 않는다.

아래 명령은 개별 시나리오를 직접 조사할 때 사용하는 저수준 실행 예시다.

### PERF-3 Smoke: API 계약과 권한 확인

```bash
docker run --rm -i \
  -e AIRS_LOADTEST_EMAIL \
  -e AIRS_LOADTEST_PASSWORD \
  -e AIRS_ANALYTICS_DATE \
  -v "$PWD/performance/k6:/scripts" \
  grafana/k6:0.54.0 run --include-system-env-vars /scripts/scenarios/smoke.js
```

### PERF-4 Baseline: 한 endpoint를 낮은 요청률로 측정

기본값은 `sensor-temperature-1mo`, 1 RPS, 3분이다. 첫 요청과 반복 요청을 비교하려면 Redis의 해당 key 상태를 별도로 기록하고, 임의의 전체 Redis flush는 절대 하지 않는다.

```bash
docker run --rm -i \
  -e AIRS_LOADTEST_EMAIL \
  -e AIRS_LOADTEST_PASSWORD \
  -e AIRS_ANALYTICS_DATE \
  -e AIRS_TARGET_RPS=1 \
  -e AIRS_TEST_DURATION=3m \
  -e AIRS_LOADTEST_ENDPOINT=sensor-temperature-1mo \
  -v "$PWD/performance/k6:/scripts" \
  grafana/k6:0.54.0 run --include-system-env-vars /scripts/scenarios/baseline.js
```

지원하는 `AIRS_LOADTEST_ENDPOINT` 값은 다음과 같다.

```text
node-list
alert-dashboard
sensor-co2-1d
sensor-temperature-1mo
analytics-co2-trend
```

### PERF-4 Mixed read: 실제 화면과 비슷한 읽기 비율

```bash
docker run --rm -i \
  -e AIRS_LOADTEST_EMAIL \
  -e AIRS_LOADTEST_PASSWORD \
  -e AIRS_ANALYTICS_DATE \
  -e AIRS_TARGET_RPS=5 \
  -e AIRS_TEST_DURATION=3m \
  -v "$PWD/performance/k6:/scripts" \
  grafana/k6:0.54.0 run --include-system-env-vars /scripts/scenarios/mixed-read.js
```

### PERF-4 Capacity ramp: 참고용

단일 ramp는 단계 사이에 서버 상태를 확인할 수 없으므로 P0 판정에는 사용하지 않는다. P0에서는 위 단계별 실행기를 사용한다.

```bash
docker run --rm -i \
  -e AIRS_LOADTEST_EMAIL \
  -e AIRS_LOADTEST_PASSWORD \
  -e AIRS_ANALYTICS_DATE \
  -e AIRS_MAX_RPS=20 \
  -e AIRS_STAGE_DURATION=3m \
  -v "$PWD/performance/k6:/scripts" \
  grafana/k6:0.54.0 run --include-system-env-vars /scripts/scenarios/capacity-ramp.js
```

### PERF-5 Cache stampede: 같은 miss를 동시에 조회

이 실험은 일반 사용 부하가 아니라 동일한 캐시 키가 비어진 순간에 여러 요청이 같은 추이를 선택하는 구간을 검증한다. 실행 직전에 **대상 응답 키 하나만** 삭제하고 같은 endpoint를 VU마다 한 번 호출한다. Redis 전체 flush나 MQTT publish는 사용하지 않는다.

라즈베리파이에서 먼저 대상 응답 캐시만 삭제한다.

```bash
cd /path/to/airs-compose
docker compose exec -T redis redis-cli DEL \
  'airs:node:sensor-trend:v1:node:node_01:metric:temperature:period:1mo'
```

그 다음 개발 Mac에서 다음을 실행한다.

```bash
docker run --rm -i \
  -e AIRS_LOADTEST_EMAIL \
  -e AIRS_LOADTEST_PASSWORD \
  -e AIRS_ANALYTICS_DATE \
  -e AIRS_STAMPEDE_VUS=5 \
  -e AIRS_STAMPEDE_METRIC=temperature \
  -e AIRS_STAMPEDE_PERIOD=1mo \
  -v "$PWD/performance/k6:/scripts" \
  grafana/k6:0.54.0 run --include-system-env-vars /scripts/scenarios/cache-stampede.js
```

성공 판단은 HTTP 200만으로 끝내지 않는다. Spring 기동 후 첫 5분 집계 로그에서 다음을 함께 확인한다.

```bash
docker compose logs --since 10m spring | grep SENSOR_TREND_METRIC
```

- `airs.node.sensor.trend.influx.load`의 `metric=temperature`, `period=1mo`, 성공 표본이 1개인지 확인한다.
- `airs.node.sensor.trend.request`의 `source=miss`가 1인지 확인한다.
- 나머지 요청은 `source=hit_after_wait` 또는 leader 저장 후 도착한 `source=hit`일 수 있다.
- `MISS_TIMEOUT_FALLBACK`이 있으면 leader가 1초 안에 결과를 저장하지 못했거나 Redis lock 경로에 문제가 있었음을 분리 조사한다.

## 중단 기준

- `http_req_failed`가 1% 이상이면 현재 단계에서 중단한다.
- `http_req_duration` P95가 1초를 초과하면 다음 요청률로 올리지 않는다.
- Raspberry Pi에서 Spring/InfluxDB/MySQL의 OOM, healthcheck 실패, MQTT telemetry 누락이 보이면 즉시 중단한다.
- API 응답이 401/403이면 부하 결과가 아니라 인증·권한 설정 문제로 분리해서 해결한다.

## 결과 기록

원본 k6 표본(`k6-raw.ndjson.gz`)과 정제된 summary, 콘솔 로그는 `performance/k6/results/`에 보관하지만 Git에는 올리지 않는다. summary에서는 `setup_data`, JWT, 이메일과 비밀번호를 제거한다. 포트폴리오와 트러블슈팅에는 다음 조건을 함께 기록한다.

```text
- 실행 날짜와 KST 시각
- 실행 머신과 네트워크 위치
- nodeId, analyticsDate, cache 상태
- endpoint 또는 혼합 비율
- RPS, 지속 시간, P50/P95/P99, 실패율
- Docker container CPU/memory, Spring metric log, Redis hit/miss
- 테스트 중단 여부와 이유
```

## 운영 관측 명령

라즈베리파이에서 테스트 전후에 아래 읽기 전용 명령으로 컨테이너 상태를 기록한다.

```bash
cd /path/to/airs-compose
docker compose ps
docker stats --no-stream airs-spring airs-redis airs-influxdb airs-mysql airs-caddy
docker compose logs --since 10m spring | grep SENSOR_TREND_METRIC
docker compose exec -T redis redis-cli INFO stats | grep -E 'keyspace_hits|keyspace_misses'
```

`SENSOR_TREND_METRIC` 로그는 `airs.node.sensor.trend.request`, Redis read/write, Influx load의 평균·P50·P95를 metric/period/cache tag별로 남긴다. Actuator는 현재 health와 info만 노출하므로, 상세 Timer는 이 로그로 읽는다.
