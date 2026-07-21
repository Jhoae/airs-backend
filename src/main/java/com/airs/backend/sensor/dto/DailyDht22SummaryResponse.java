package com.airs.backend.sensor.dto;

import java.time.Instant;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
// 한 노드의 하루 온도·습도 요약값을 반환한다.
public class DailyDht22SummaryResponse {

    // 요약 대상 노드 ID다.
    private String nodeId;
    // 한국 시간 기준 요약 날짜다.
    private LocalDate date;
    // 하루 중 가장 높은 온도다.
    private Double peakTemperature;
    // 최고 온도가 기록된 시각이다.
    private Instant peakTemperatureTime;
    // 하루 온도의 산술 평균이다.
    private Double averageTemperature;
    // 하루 중 가장 낮은 온도다.
    private Double minTemperature;
    // 최저 온도가 기록된 시각이다.
    private Instant minTemperatureTime;
    // 하루 중 가장 높은 습도다.
    private Double peakHumidity;
    // 최고 습도가 기록된 시각이다.
    private Instant peakHumidityTime;
    // 하루 습도의 산술 평균이다.
    private Double averageHumidity;
    // 하루 중 가장 낮은 습도다.
    private Double minHumidity;
    // 최저 습도가 기록된 시각이다.
    private Instant minHumidityTime;
}
