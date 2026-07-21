import "date"

// 직전 완료 1시간만 집계하고 1분 여유로 경계 telemetry를 수신합니다.
option task = {
  name: "airs-rollup-1h",
  every: 1h,
  offset: 1m,
}

rawBucket = "airs"
rollupBucket = "airs_rollup"

currentHourStart = date.truncate(t: now(), unit: 1h)
completedHourStart = date.add(d: -1h, to: currentHourStart)

rawCo2 =
  from(bucket: rawBucket)
    |> range(start: completedHourStart, stop: currentHourStart)
    |> filter(fn: (r) => r._measurement == "sensor_data")
    |> filter(fn: (r) => r._field == "co2_ppm")
    |> keep(columns: ["_time", "_value", "node_id"])

rawTemperature =
  from(bucket: rawBucket)
    |> range(start: completedHourStart, stop: currentHourStart)
    |> filter(fn: (r) => r._measurement == "sensor_data")
    |> filter(fn: (r) => r._field == "temperature_c")
    |> keep(columns: ["_time", "_value", "node_id"])

rawHumidity =
  from(bucket: rawBucket)
    |> range(start: completedHourStart, stop: currentHourStart)
    |> filter(fn: (r) => r._measurement == "sensor_data")
    |> filter(fn: (r) => r._field == "humidity_pct")
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

temperatureMean =
  rawTemperature
    |> aggregateWindow(every: 1h, fn: mean, createEmpty: false)
    |> set(key: "_field", value: "temperature_mean")

temperatureMin =
  rawTemperature
    |> aggregateWindow(every: 1h, fn: min, createEmpty: false)
    |> set(key: "_field", value: "temperature_min")

temperatureMax =
  rawTemperature
    |> aggregateWindow(every: 1h, fn: max, createEmpty: false)
    |> set(key: "_field", value: "temperature_max")

temperatureCount =
  rawTemperature
    |> aggregateWindow(every: 1h, fn: count, createEmpty: false)
    |> set(key: "_field", value: "temperature_count")

humidityMean =
  rawHumidity
    |> aggregateWindow(every: 1h, fn: mean, createEmpty: false)
    |> set(key: "_field", value: "humidity_mean")

humidityMin =
  rawHumidity
    |> aggregateWindow(every: 1h, fn: min, createEmpty: false)
    |> set(key: "_field", value: "humidity_min")

humidityMax =
  rawHumidity
    |> aggregateWindow(every: 1h, fn: max, createEmpty: false)
    |> set(key: "_field", value: "humidity_max")

humidityCount =
  rawHumidity
    |> aggregateWindow(every: 1h, fn: count, createEmpty: false)
    |> set(key: "_field", value: "humidity_count")

// count는 정수이고 나머지는 실수이므로 각 field stream을 따로 저장합니다.
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

// 온도·습도도 같은 시각·node_id 기준의 평균·최소·최대·건수 field로 저장합니다.
temperatureMean
  |> set(key: "_measurement", value: "sensor_rollup_1h")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])

temperatureMin
  |> set(key: "_measurement", value: "sensor_rollup_1h")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])

temperatureMax
  |> set(key: "_measurement", value: "sensor_rollup_1h")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])

temperatureCount
  |> set(key: "_measurement", value: "sensor_rollup_1h")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])

humidityMean
  |> set(key: "_measurement", value: "sensor_rollup_1h")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])

humidityMin
  |> set(key: "_measurement", value: "sensor_rollup_1h")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])

humidityMax
  |> set(key: "_measurement", value: "sensor_rollup_1h")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])

humidityCount
  |> set(key: "_measurement", value: "sensor_rollup_1h")
  |> to(bucket: rollupBucket, tagColumns: ["node_id"])
