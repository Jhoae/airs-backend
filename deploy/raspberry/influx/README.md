# InfluxDB Rollup Deployment

`airs`의 raw `sensor_data`는 삭제하지 않고, `airs_rollup`에 재생성 가능한 CO2 집계만 저장합니다.

```text
raw bucket:      airs
raw measurement: sensor_data
rollup bucket:   airs_rollup
rollup measures: sensor_rollup_1h, sensor_rollup_1d
tag:             node_id
fields:          co2_mean, co2_min, co2_max, co2_count
```

`airs-rollup-1h`는 매시 정각 뒤 1분에 직전 완료 1시간을 저장합니다. `airs-rollup-1d`는 UTC 자정 뒤 1분에 직전 완료 하루를 저장합니다. 두 Task 모두 진행 중인 시간 구간을 집계하지 않으며, 지연 수신 데이터나 이전 구간 재생성은 raw를 기준으로 수동 backfill합니다.

## Deploy or update

Run this on the Raspberry Pi as `sogangairs` after the backend repository is current:

```bash
cd /home/sogangairs/service/backend
bash deploy/raspberry/influx/upsert-rollup-task.sh
```

스크립트는 `airs_rollup` bucket이 없을 때만 무기한 보관으로 만들고, Git에서 관리하는 1시간·1일 Flux 파일을 각각 생성 또는 갱신합니다. 인증 토큰은 `airs-influxdb` 컨테이너 안의 CLI 설정을 사용하므로 저장소에 넣지 않습니다.

`verify-rollup-1h.sh` is intentionally not part of the automated deployment. It is a read-only Raspberry Pi operational verification tool: an operator runs it after a task change to compare one closed raw hour with its derived rollup point. It does not create tasks, write sensor data, or run as a background process.

## Backfill historical closed hours

`backfill-rollup-1h.sh` is also manual. It fills a selected range of **derived** hourly CO2 points from retained raw data when the Task was introduced after telemetry already existed, or when a previously derived range must be rebuilt. It never deletes or edits raw `sensor_data`, and it is safe to rerun because InfluxDB merges fields with the same measurement, `node_id`, and timestamp.

Both timestamps must be exact UTC hour boundaries. The end timestamp is exclusive and must not include the current partial hour.

```bash
cd /home/sogangairs/service/backend
bash deploy/raspberry/influx/backfill-rollup-1h.sh \
  2026-07-05T02:00:00Z \
  2026-07-20T12:00:00Z
```

## Backfill historical closed days

`backfill-rollup-1d.sh`도 수동 실행입니다. UTC 하루 경계의 원본 CO2를 `sensor_rollup_1d`로 다시 저장하며, raw `sensor_data`는 수정하거나 삭제하지 않습니다.

```bash
cd /home/sogangairs/service/backend
bash deploy/raspberry/influx/backfill-rollup-1d.sh \
  2026-07-06T00:00:00Z \
  2026-07-21T00:00:00Z
```

## Verify one closed hour

Use a fully closed UTC hour. The following compares raw CO2 mean/min/max/count to the corresponding rollup point.

```bash
cd /home/sogangairs/service/backend
bash deploy/raspberry/influx/verify-rollup-1h.sh \
  node_01 \
  2026-07-20T01:00:00Z \
  2026-07-20T02:00:00Z
```

The rollup task must have run after the selected hour. `co2_count` is retained as a data-quality signal; it is not an averaging weight.
