package com.airs.backend.sensor.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
// AI 규칙 평가가 사용할 최신 측정값과 시간창 추세를 담는다.
public class AiSensorTrendData {

    // 조회 창에서 가장 최근의 온도·습도·CO2 측정값이다.
    private Dht22MeasurementItem latestMeasurement;
    // 현재 CO2와 10분 전 CO2의 차이(ppm)다.
    private Double co2Rate10m;
    // 최근 조회 창에서 CO2가 1,000ppm을 넘은 누적 시간(분)이다.
    private Integer co2Over1000Minutes;
    // 최근 30분 온도 변화량(°C)이다.
    private Double tempRate30m;
    // 마지막 움직임 이후 지난 시간(분)이다.
    private Integer noOccupancyMinutes;
}
