package com.airs.backend.sensor.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@NoArgsConstructor
@AllArgsConstructor
// 차트 한 점에 필요한 시간과 평균 CO2 값을 담는다.
public class Co2TrendItem {

    // 집계 구간을 대표하는 UTC 시각이다.
    private Instant timestamp;
    // 해당 시간 구간의 평균 CO2 농도(ppm)다.
    private Integer co2Ppm;
}
