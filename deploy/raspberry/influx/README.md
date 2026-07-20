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

The task runs every 15 minutes and reprocesses the two most recently completed hours. It therefore updates the same node/hour point when a delayed telemetry message is eventually stored, without creating a partial-hour point with the task execution time as its timestamp.

## Deploy or update

Run this on the Raspberry Pi as `sogangairs` after the backend repository is current:

```bash
cd /home/sogangairs/service/backend
bash deploy/raspberry/influx/upsert-rollup-task.sh
```

The script creates `airs_rollup` with infinite retention only when it does not exist. It then creates the `airs-rollup-1h` task, or updates the existing task with the Git-managed Flux file. It reads the authenticated Influx CLI configuration already held by the `airs-influxdb` container; no token is stored in this repository.

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
