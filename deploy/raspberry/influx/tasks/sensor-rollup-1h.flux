import "date"

// Reprocess completed hours as well as the current partial hour so delayed MQTT
// writes can correct an already-created rollup point on the next task run.
option task = {
  name: "airs-rollup-1h",
  every: 15m,
  offset: 1m,
}

rawBucket = "airs"
rollupBucket = "airs_rollup"

currentHourStart = date.truncate(t: now(), unit: 1h)
reprocessStart = date.add(d: -2h, to: currentHourStart)

rawCo2 =
  from(bucket: rawBucket)
    |> range(start: reprocessStart, stop: now())
    |> filter(fn: (r) => r._measurement == "sensor_data")
    |> filter(fn: (r) => r._field == "co2_ppm")
    |> keep(columns: ["_time", "_value", "node_id"])

co2Mean =
  rawCo2
    |> aggregateWindow(every: 1h, fn: mean, createEmpty: false)
    |> set(key: "_field", value: "co2_mean")

co2Min =
  rawCo2
    |> aggregateWindow(every: 1h, fn: min, createEmpty: false)
    |> set(key: "_field", value: "co2_min")

co2Max =
  rawCo2
    |> aggregateWindow(every: 1h, fn: max, createEmpty: false)
    |> set(key: "_field", value: "co2_max")

co2Count =
  rawCo2
    |> aggregateWindow(every: 1h, fn: count, createEmpty: false)
    |> set(key: "_field", value: "co2_count")

// Write each field stream separately. `count` is an integer while the three
// statistical values are floats, so unioning them would cause a Flux type clash.
co2Mean
  |> set(key: "_measurement", value: "sensor_rollup_1h")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])

co2Min
  |> set(key: "_measurement", value: "sensor_rollup_1h")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])

co2Max
  |> set(key: "_measurement", value: "sensor_rollup_1h")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])

co2Count
  |> set(key: "_measurement", value: "sensor_rollup_1h")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])
