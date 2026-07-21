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

rawTemperature =
  from(bucket: rawBucket)
    |> range(start: completedDayStart, stop: currentDayStart)
    |> filter(fn: (r) => r._measurement == "sensor_data")
    |> filter(fn: (r) => r._field == "temperature_c")
    |> keep(columns: ["_time", "_value", "node_id"])

rawHumidity =
  from(bucket: rawBucket)
    |> range(start: completedDayStart, stop: currentDayStart)
    |> filter(fn: (r) => r._measurement == "sensor_data")
    |> filter(fn: (r) => r._field == "humidity_pct")
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

temperatureMean =
  rawTemperature
    |> aggregateWindow(every: 1d, fn: mean, createEmpty: false)
    |> set(key: "_field", value: "temperature_mean")

temperatureMin =
  rawTemperature
    |> aggregateWindow(every: 1d, fn: min, createEmpty: false)
    |> set(key: "_field", value: "temperature_min")

temperatureMax =
  rawTemperature
    |> aggregateWindow(every: 1d, fn: max, createEmpty: false)
    |> set(key: "_field", value: "temperature_max")

temperatureCount =
  rawTemperature
    |> aggregateWindow(every: 1d, fn: count, createEmpty: false)
    |> set(key: "_field", value: "temperature_count")

humidityMean =
  rawHumidity
    |> aggregateWindow(every: 1d, fn: mean, createEmpty: false)
    |> set(key: "_field", value: "humidity_mean")

humidityMin =
  rawHumidity
    |> aggregateWindow(every: 1d, fn: min, createEmpty: false)
    |> set(key: "_field", value: "humidity_min")

humidityMax =
  rawHumidity
    |> aggregateWindow(every: 1d, fn: max, createEmpty: false)
    |> set(key: "_field", value: "humidity_max")

humidityCount =
  rawHumidity
    |> aggregateWindow(every: 1d, fn: count, createEmpty: false)
    |> set(key: "_field", value: "humidity_count")

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

// 온도·습도도 같은 시각·node_id 기준의 평균·최소·최대·건수 field로 저장합니다.
temperatureMean
  |> set(key: "_measurement", value: "sensor_rollup_1d")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])

temperatureMin
  |> set(key: "_measurement", value: "sensor_rollup_1d")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])

temperatureMax
  |> set(key: "_measurement", value: "sensor_rollup_1d")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])

temperatureCount
  |> set(key: "_measurement", value: "sensor_rollup_1d")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])

humidityMean
  |> set(key: "_measurement", value: "sensor_rollup_1d")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])

humidityMin
  |> set(key: "_measurement", value: "sensor_rollup_1d")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])

humidityMax
  |> set(key: "_measurement", value: "sensor_rollup_1d")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])

humidityCount
  |> set(key: "_measurement", value: "sensor_rollup_1d")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])
