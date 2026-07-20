import "date"

// 완료된 UTC 하루만 집계해 진행 중인 날짜의 부분 데이터를 저장하지 않는다.
option task = {
  name: "airs-rollup-1d",
  every: 1d,
  offset: 1m,
}

rawBucket = "airs"
rollupBucket = "airs_rollup"

currentDayStart = date.truncate(t: now(), unit: 1d)
completedDayStart = date.add(d: -1d, to: currentDayStart)

rawCo2 =
  from(bucket: rawBucket)
    |> range(start: completedDayStart, stop: currentDayStart)
    |> filter(fn: (r) => r._measurement == "sensor_data")
    |> filter(fn: (r) => r._field == "co2_ppm")
    |> keep(columns: ["_time", "_value", "node_id"])

co2Mean =
  rawCo2
    |> aggregateWindow(every: 1d, fn: mean, createEmpty: false)
    |> set(key: "_field", value: "co2_mean")

co2Min =
  rawCo2
    |> aggregateWindow(every: 1d, fn: min, createEmpty: false)
    |> set(key: "_field", value: "co2_min")

co2Max =
  rawCo2
    |> aggregateWindow(every: 1d, fn: max, createEmpty: false)
    |> set(key: "_field", value: "co2_max")

co2Count =
  rawCo2
    |> aggregateWindow(every: 1d, fn: count, createEmpty: false)
    |> set(key: "_field", value: "co2_count")

// count는 정수, 나머지는 실수이므로 네 stream을 각각 저장한다.
co2Mean
  |> set(key: "_measurement", value: "sensor_rollup_1d")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])

co2Min
  |> set(key: "_measurement", value: "sensor_rollup_1d")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])

co2Max
  |> set(key: "_measurement", value: "sensor_rollup_1d")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])

co2Count
  |> set(key: "_measurement", value: "sensor_rollup_1d")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])
