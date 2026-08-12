# MQTT telemetry v2 event-time 계약

## 적용 범위

이 문서는 종료된 AIRS 프로젝트의 backend 계약과 가상 테스트 기준을 정의한다. 실제 펌웨어, Mosquitto, Raspberry Pi에는 적용하지 않았으며 운영 배포도 수행하지 않았다.

## payload 계약

```json
{
  "node_id": "node_01",
  "boot_id": "boot-a",
  "sequence_no": 43,
  "observed_at": "2026-08-12T05:59:55.000Z",
  "temperature_c": 24.3,
  "humidity_pct": 52.0,
  "co2_ppm": 820,
  "pir_detected": 0,
  "mmwave_detected": 1,
  "wifi_signal_dbm": -58
}
```

`boot_id`, `sequence_no`, `observed_at`은 필수다. `observed_at`은 ISO-8601 UTC `Instant` 형식이다. node ID의 신뢰 원천은 구독 topic `airs/node/{nodeId}/telemetry`이며 예시의 `node_id`는 가상 장비 payload를 읽기 쉽게 표시한 값이다.

## 두 시간의 의미

| 시간 | 생성 주체 | 용도 |
| --- | --- | --- |
| `observed_at` | 센서 | InfluxDB `_time`, 센서 그래프, 변화량 계산 |
| `received_at` | Spring MQTT callback | 마지막 수신 시각, 연결 상태, 수집 지연 |

`sequence_no`는 같은 boot session의 순서를 알려줄 뿐 실제 시간 간격을 표현하지 못한다. sequence 42와 43 사이가 5초인지 5분인지 알 수 없으므로 그래프와 변화율 계산에는 `observed_at`이 필요하다.

## 전달 판정

| 판정 | 조건 | InfluxDB raw | MySQL 최신 snapshot | occupancy·실시간 alert | ingestion 최신 순서 |
| --- | --- | --- | --- | --- | --- |
| `ACCEPTED_CURRENT` | 고유하고 최신인 이벤트 | 저장 | 갱신 | 실행 | 전진 |
| `ACCEPTED_LATE` | 최신 순서보다 늦게 도착한 고유 이벤트 | 저장 | 유지 | 실행하지 않음 | 유지 |
| `DUPLICATE` | 같은 `nodeId + bootId + sequenceNo`가 처리 창 안에 존재 | 저장하지 않음 | 유지 | 실행하지 않음 | 유지 |

late telemetry는 과거 시점의 상태를 현재 메모리로 다시 계산하지 않는다. 따라서 원본 센서 field와 전달 metadata만 저장하고 `occupancy_state`, `occupancy_present`, `minutes_since_motion`은 기록하지 않는다.

## 예시: 42, 44, 43

```text
42: observed 14:00:00, received 14:00:01, CO2 800 -> ACCEPTED_CURRENT
44: observed 14:00:10, received 14:00:11, CO2 850 -> ACCEPTED_CURRENT + sequence gap 로그
43: observed 14:00:05, received 14:00:15, CO2 820 -> ACCEPTED_LATE
43 재전송                                      -> DUPLICATE
```

InfluxDB에는 event time 순서로 `14:00:00=800`, `14:00:05=820`, `14:00:10=850`이 존재한다. MySQL 최신 snapshot과 ingestion state는 sequence 44를 유지하며 sequence 43은 occupancy와 alert에 영향을 주지 않는다.

## boot session 정책

- 같은 `boot_id`에서는 큰 `sequence_no`와 더 늦은 `observed_at`을 current로 처리한다.
- 다른 `boot_id`가 현재 최신 `observed_at`보다 늦으면 정상 재부팅으로 보고 current로 처리한다.
- 새 boot를 처리한 뒤 과거 `observed_at`을 가진 이전 boot 이벤트가 오면 late로 저장한다.
- 새 boot의 같은 event key가 다시 오면 duplicate다.

센서 시각이 신뢰 가능한 계약이라는 전제에서 새 boot를 판정한다. 서버는 미래 시각을 무제한 신뢰하지 않으며 `sensor.telemetry.reliability.max-future-skew-millis` 범위까지만 허용한다.

## 멱등성 설계

### 검토한 대안

1. 영구 MySQL dedup 테이블은 가장 긴 중복 차단 이력을 제공하지만 5초 telemetry마다 행이 무제한 증가한다.
2. Redis `SET NX`와 TTL은 빠르고 크기를 제한할 수 있지만 Redis 기록 후 MySQL rollback이 발생하면 처리되지 않은 이벤트를 duplicate로 오판할 수 있다.
3. 현재 MySQL transaction과 outbox를 재사용하면 순서 상태, snapshot, outbox가 함께 commit되거나 rollback된다.

### 선택한 방식

`telemetry_ingestion_states`의 node row를 `FOR UPDATE`로 잠그고, `telemetry_outbox.event_key = nodeId|bootId|sequenceNo`를 bounded dedup ledger로 사용한다. 같은 node를 여러 Spring 인스턴스가 처리해도 row lock 안에서 순차 판정하며, transaction rollback 전에는 state와 event key가 확정되지 않는다.

완료 outbox는 설정된 보관 기간 뒤 삭제된다. 일반적인 MQTT 재전달은 그 기간 안에서 차단하지만, 정리 이후 아주 오래된 late 이벤트의 재전송까지 영구 차단하지는 않는다. 그 경우 InfluxDB point identity인 measurement, `node_id` tag, `observed_at`이 같아 기존 point를 덮어쓰지만 불필요한 재전송 쓰기는 발생할 수 있다. `sequence_no`는 높은 cardinality를 피하기 위해 tag가 아닌 field로 저장한다.

## outbox schema v2

outbox payload는 `observedAt`, `receivedAt`, `ingestDelayMillis`, `bootId`, `sequenceNo`, `deliveryDecision`을 포함하는 schema version 2다. 프로젝트가 종료되어 실제 queue를 마이그레이션하지 않으므로 v1 dual reader는 추가하지 않았다. 실제 운영 전환이라면 v1 pending outbox를 모두 drain한 뒤 publisher와 producer를 같은 revision으로 교체해야 한다.

## 검증 범위와 후속 과제

- mock 기반 단위 테스트로 필수 필드, 미래 시각, current·late·duplicate, Influx point 시간을 검증한다.
- Testcontainers MySQL 통합 테스트로 row lock transaction, rollback, 42·44·43, boot 전환을 검증한다.
- 가상 MQTT JSON을 subscriber, dispatcher, ingestion service까지 전달한다.
- 실제 센서 clock drift, 실제 broker 재전달, 다중 Spring 프로세스, 실제 InfluxDB write는 검증하지 않았다.
- 이미 집계가 끝난 rollup 시간 구간에 late raw가 들어오면 해당 구간을 재집계하는 정책이 필요하다.
