package com.airs.backend.sensor.dto;

import java.util.Arrays;

// 노드 상세 그래프에서 선택할 원본·rollup field 정보를 한곳에 모읍니다.
public enum SensorTrendMetric {

    // 온도 원본 field와 rollup 평균·건수 field를 정의합니다.
    TEMPERATURE("temperature", "temperature_c", "temperature_mean", "temperature_count"),
    // 습도 원본 field와 rollup 평균·건수 field를 정의합니다.
    HUMIDITY("humidity", "humidity_pct", "humidity_mean", "humidity_count"),
    // CO2 원본 field와 rollup 평균·건수 field를 정의합니다.
    CO2("co2", "co2_ppm", "co2_mean", "co2_count");

    // API가 받는 지표 문자열입니다.
    private final String apiValue;
    // raw sensor_data에서 읽을 Influx field 이름입니다.
    private final String rawField;
    // rollup measurement에서 읽을 평균 field 이름입니다.
    private final String meanField;
    // rollup 평균의 원본 표본 수를 나타내는 field 이름입니다.
    private final String countField;

    // 지표별 API·raw·rollup field 이름을 생성합니다.
    SensorTrendMetric(String apiValue, String rawField, String meanField, String countField) {
        // 외부 요청에 노출할 안정적인 지표 이름을 저장합니다.
        this.apiValue = apiValue;
        // raw telemetry 조회 조건에 쓸 field 이름을 저장합니다.
        this.rawField = rawField;
        // rollup 평균 조회 조건에 쓸 field 이름을 저장합니다.
        this.meanField = meanField;
        // 가중 평균 계산과 coverage 검증에 쓸 field 이름을 저장합니다.
        this.countField = countField;
    }

    // API 문자열을 대소문자와 무관하게 enum 값으로 변환합니다.
    public static SensorTrendMetric fromApiValue(String value) {
        // metric을 생략하면 어떤 sensor field를 읽을지 결정할 수 없습니다.
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("metric은 temperature, humidity, co2 중 하나여야 합니다.");
        }

        // 허용한 세 지표 중 일치하는 값을 찾아 반환합니다.
        return Arrays.stream(values())
                .filter(metric -> metric.apiValue.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "metric은 temperature, humidity, co2 중 하나여야 합니다."
                ));
    }

    // 응답 JSON에 반환할 지표 문자열을 제공합니다.
    public String getApiValue() {
        return apiValue;
    }

    // raw telemetry Flux의 _field 조건에 사용할 이름을 제공합니다.
    public String getRawField() {
        return rawField;
    }

    // rollup Flux의 평균 field 조건에 사용할 이름을 제공합니다.
    public String getMeanField() {
        return meanField;
    }

    // rollup Flux의 count field 조건에 사용할 이름을 제공합니다.
    public String getCountField() {
        return countField;
    }
}
