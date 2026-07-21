package com.airs.backend.sensor.dto;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
// InfluxDB에서 읽은 한 시점의 대표 센서값을 담는다.
public class Dht22MeasurementItem {

    // 해당 시점의 온도(°C)다.
    private Double temperature;
    // 해당 시점의 습도(%)다.
    private Double humidity;
    // 해당 시점의 CO2 농도(ppm)다.
    private Integer co2Ppm;
    // 측정 또는 집계 point의 UTC 시각이다.
    private Instant timestamp;

    // CO2가 없는 기존 온습도 조회 결과도 같은 DTO로 만들기 위한 생성자다.
    public Dht22MeasurementItem(Double temperature, Double humidity, Instant timestamp) {
        // 읽은 온도를 저장한다.
        this.temperature = temperature;
        // 읽은 습도를 저장한다.
        this.humidity = humidity;
        // 원본 또는 집계 시각을 저장한다.
        this.timestamp = timestamp;
    }
}
