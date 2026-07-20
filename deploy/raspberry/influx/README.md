# InfluxDB Rollup Deployment

`sensor-rollup-1h.flux` converts the raw `airs/sensor_data` CO2 field into hourly rollups.

```text
raw bucket:      airs
raw measurement: sensor_data
rollup bucket:   airs_rollup
rollup measure:  sensor_rollup_1h
tag:             node_id
fields:          co2_mean, co2_min, co2_max, co2_count
```

The task runs once per hour, one minute after the hour boundary, and aggregates only the preceding completed hour. It never includes the current partial hour, so its timestamp remains on an exact hour boundary. This intentionally does not repair telemetry that arrives after that one-time aggregation; raw `sensor_data` remains available for a later manual backfill if needed.

## Deploy or update

Run this on the Raspberry Pi as `sogangairs` after the backend repository is current:

```bash
cd /home/sogangairs/service/backend
bash deploy/raspberry/influx/upsert-rollup-task.sh
```

The script creates `airs_rollup` with infinite retention only when it does not exist. It then creates the `airs-rollup-1h` task, or updates the existing task with the Git-managed Flux file. It reads the authenticated Influx CLI configuration already held by the `airs-influxdb` container; no token is stored in this repository.

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
