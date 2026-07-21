package com.airs.backend.sensor.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

// 온도·습도·CO2가 공통으로 사용하는 시간별 평균 그래프 point입니다.
@Getter
@AllArgsConstructor
public class SensorTrendItem {

    // 집계 구간을 대표하는 UTC 시각입니다.
    private Instant timestamp;
    // 선택한 센서 지표의 평균값입니다.
    private Double value;
}
