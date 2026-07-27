# AIRS 기술 도입 및 운영 판단 기록

이 문서는 AIRS에 적용한 기술을 단순 목록이 아니라, 해결하려는 문제와 선택 근거, 현재 한계, 다음 보완 작업까지 함께 기록한다. 포트폴리오와 기술 면접에서는 결과만 말하지 않고 이 판단 과정을 근거로 설명한다.

## MQTT - IoT telemetry 전달

### 해결하려는 문제

노드는 약 5초마다 `airs/node/node_01/telemetry`에 온도, 습도, CO2, PIR, mmWave, Wi-Fi RSSI처럼 작은 측정값을 반복 전송한다. Spring 외에도 향후 저장, 모니터링, 분석 서비스가 같은 데이터를 받을 수 있어야 한다.

### 선택과 근거

MQTT는 IoT라는 이유만으로 선택한 프로토콜이 아니다. 센서는 broker의 topic에 발행하고, Spring은 필요한 topic만 구독하는 pub/sub 구조를 제공한다. 따라서 노드가 Spring의 주소나 분석 서비스의 존재를 알 필요가 없고, 새 소비자가 생겨도 펌웨어의 전송 대상을 바꾸지 않아도 된다. 연결을 매번 새로 만드는 HTTP POST보다 작은 telemetry를 반복적으로 보내는 상황에 적합하다.

HTTP POST는 요청/응답이 분명하고 디버깅이 쉬우며 명령형 API에는 적합하다. 다만 노드 수와 소비자가 늘어날수록 각 기기가 서버 주소, 연결 실패 재시도, 소비자 추가를 직접 관리해야 한다. AIRS의 지속 telemetry에는 MQTT가 더 자연스럽다고 판단했다.

### 실제 AIRS 흐름

```text
node_01
  -> topic: airs/node/node_01/telemetry
  -> Mosquitto broker
  -> Spring MqttDht22Subscriber
  -> Dht22IngestionService
  -> MySQL 최신 snapshot + InfluxDB raw sensor_data
```

예를 들어 `{ "temperature_c": 24.3, "humidity_pct": 53.2, "co2_ppm": 842, "pir_detected": 1 }` payload를 받으면 Spring은 node ID를 topic에서 분리하고, 최신 화면용 MySQL snapshot과 시계열 분석용 InfluxDB raw point에 각각 반영한다.

### 현재 전달 보장과 중복 위험

현재 Spring Paho 구독자는 QoS 1을 요청하지만, 저장소의 ESP `PubSubClient` 라이브러리는 publish QoS 0만 지원한다. MQTT 실제 전달 QoS는 발행과 구독 QoS 중 낮은 값으로 협상되므로, 현재 펌웨어 경로의 telemetry는 QoS 0으로 전달될 가능성을 우선 확인해야 한다. 즉 현재의 더 큰 위험은 QoS 1 재전송에 의한 중복보다 QoS 0 유실이다.

그럼에도 펌웨어 재시도, 브리지 재발행, 향후 QoS 1 발행으로 같은 telemetry가 다시 들어올 수 있으므로 소비자는 멱등성을 준비해야 한다. MySQL 최신 snapshot은 같은 노드 행을 갱신하므로 값이 크게 누적되지는 않지만, `OccupancyFusionService`의 연속 PIR·마지막 움직임 이력은 같은 메시지를 두 번 처리하면 판정에 영향을 받을 수 있다.

### 다음 개선 - 메시지 식별자와 Redis 중복 제거

펌웨어 payload에 `boot_id`와 `sequence_no`를 추가한다. `boot_id`는 기기가 켜질 때마다 새로 만드는 식별자이고, `sequence_no`는 그 부팅 동안 telemetry마다 1씩 증가한다. 예시는 다음과 같다.

```json
{
  "node_id": "node_01",
  "boot_id": "7f3a9c2e",
  "sequence_no": 1842,
  "temperature_c": 24.3,
  "humidity_pct": 53.2,
  "co2_ppm": 842
}
```

Spring은 재실 융합보다 먼저 Redis에 `SET airs:mqtt:dedup:node_01:7f3a9c2e:1842 1 NX EX 600`을 시도한다. `NX`는 키가 아직 없을 때만 저장하라는 원자 조건이고, `EX 600`은 600초 뒤 키를 자동 삭제하는 TTL이다. 처음 수신은 키 생성에 성공해 적재한다. 같은 메시지가 재전송되면 키가 이미 있으므로 저장이 실패하고 MySQL, InfluxDB, 재실 이력 갱신을 모두 건너뛴다.

`nodeId + sequence_no`만 쓰면 기기 재부팅 후 sequence가 0부터 다시 시작할 때 이전 키와 충돌할 수 있다. 그래서 재부팅 구분자인 `boot_id`를 함께 사용하거나, 전원이 꺼져도 유지되는 단조 증가 번호를 사용해야 한다. 현재 telemetry에는 두 필드가 없으므로 이 기능은 아직 구현·활성화하지 않는다.

### MQTT 보안 강화 순서

MQTT TLS, 기기 인증, topic ACL은 Spring만의 작업이 아니다. Mosquitto broker 설정, 펌웨어 연결 방식, Spring 연결 설정을 함께 바꾸는 운영 작업이다. 준비 없이 적용하면 노드 telemetry가 즉시 끊길 수 있으므로 아래 순서로 진행한다.

1. broker의 외부 1883 노출과 실제 센서 접속 경로를 점검한다.
2. Mosquitto에 TLS listener(일반적으로 8883), 서버 인증서, 비밀번호 파일, ACL을 준비한다.
3. 노드마다 별도 계정 또는 인증서를 발급하고 `airs/node/{자신의-node-id}/telemetry`만 발행하도록 ACL을 제한한다.
4. ESP 펌웨어를 TLS client와 자격 증명으로 변경하고, 시험 노드에서 연결·재연결을 검증한다.
5. Spring도 broker 계정과 TLS 연결을 적용한 뒤, plain 1883 외부 노출을 제거한다.

Mosquitto의 인증서·비밀번호·ACL 파일은 라즈베리파이의 broker volume에 있고, Spring은 현재 그 broker에 TCP로 연결한다. 따라서 이 작업은 backend, HW/firmware, 라즈베리파이 broker 운영을 함께 포함한다.

## CORS - 브라우저 교차 출처 요청 제한

기존 `CorsConfig`의 `/**`는 모든 URL 경로에 같은 CORS 정책을 적용한다는 뜻일 뿐, 인증과 인가를 해제하지는 않는다. 실제 위험을 넓혔던 값은 `allowed-origins=*`였다. 이는 어떤 웹사이트 origin도 브라우저에서 허용된 API 응답을 읽을 수 있게 한다.

현재는 여러 웹·앱 개발 환경의 연결을 우선하기 위해 `AIRS_CORS_ALLOWED_ORIGINS=*`를 사용한다. `CorsConfig`는 `allowedOriginPatterns`로 이를 처리하고, API 경로는 계속 `/airs/**`로 한정한다. 이는 인증과 인가를 해제하는 설정은 아니지만, 어떤 웹 origin도 브라우저에서 API 응답을 읽을 수 있게 하므로 운영 전에는 `https://airs.bibnear.cloud`처럼 공식 origin만 허용하도록 반드시 되돌린다. 메서드는 `GET, POST, PUT, PATCH, DELETE, OPTIONS`, 요청 header는 `Authorization, Content-Type`로 한정한다.

## k6 - 인증된 읽기 API의 재현 가능한 부하 검증

### 해결하려는 문제

현재 운영 센서는 `node_01` 한 대여서 실제 트래픽이 서비스 한계나 cache hit/miss 비용을 충분히 드러내지 않는다. 화면이 정상 동작하는지와 요청률이 증가할 때 오류율·P95·컨테이너 자원이 안정적인지는 다른 문제다.

### 선택과 근거

`backend/performance/k6/`에 k6 시나리오를 두었다. k6는 JavaScript 기반으로 `constant-arrival-rate`, `ramping-arrival-rate`, threshold를 선언할 수 있어 VU 수가 아니라 초당 시작 요청 수(RPS)를 기준으로 실험을 표현하기 좋다. Docker image로 로컬에서만 실행하므로 운영 compose에 새로운 상시 컨테이너를 추가하지 않는다.

각 실행은 `setup()`에서 관리자 로그인 한 번으로 JWT를 받고, 이후에는 `Authorization: Bearer`를 붙인 GET만 요청한다. 이메일과 비밀번호는 실행 환경변수로만 전달하며 Git, README 예시, 결과 JSON에 기록하지 않는다. 결과 JSON은 운영 응답 정보가 담길 수 있어 Git에서 제외한다.

### 운영 보호 기준

- 쓰기 API, MQTT publish, MySQL/Influx 직접 쓰기, Redis 전체 flush는 금지한다.
- 초당 요청률은 초기에는 20RPS를 상한으로 둔다.
- HTTP 실패율 1% 이상, P95가 1초 이상, health 실패, CPU·메모리 위험 신호가 보이면 다음 단계를 중단한다.
- cache miss 실험은 전체 Redis가 아니라 대상 key 하나만 통제하고 낮은 동시성에서 별도로 수행한다.

### 2026-07-27 1차 검증

외부 HTTPS 기준으로 온도 1개월 API를 1RPS로 1분간 호출했을 때 62개 HTTP 요청의 평균은 308.92ms, P95는 338.96ms, 실패는 0건이었다. 혼합 읽기 API를 20RPS로 1분간 유지했을 때는 1,202개 요청의 평균 296.24ms, P95 305.19ms, 실패 0건을 기록했다. 단, 이 결과는 개발 Mac에서 단일 Raspberry Pi로 향한 짧은 읽기 부하일 뿐 다수 노드 MQTT ingest 또는 장시간 처리량을 보장하지 않는다.

Spring 내부 `NodeSensorTrendMetrics`는 같은 기간 `temperature + 1mo` cache hit가 평균 4.78ms, miss가 평균 43.61ms였음을 기록했다. hit/miss 차이는 Redis cache와 Influx hourly rollup 경로가 분리되어 있음을 보여준다. 외부 HTTPS 시간에는 TLS·Caddy·네트워크가 함께 포함되므로 두 계층의 수치를 직접 비교하거나 합산하지 않는다.

### 현재 한계와 다음 선택

현재 측정에서 오류율 증가, Redis eviction, health 실패, memory pressure가 보이지 않아 무작정 JVM/DB 설정을 바꾸지 않았다. 다음 개선은 `5d`·`1mo` rollup coverage, cache miss 동시성, 수분 이상 staged ramp를 같은 endpoint·기간·cache 조건으로 분리해 재측정한 뒤 선택한다.
