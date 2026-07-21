package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

@Getter
@AllArgsConstructor
public class AdminCo2TrendPointResponse {

    // 이 시간 구간 평균값을 대표하는 UTC 시각이다.
    private Instant timestamp;
    // 해당 시간 구간의 캠퍼스 평균 CO2 농도다.
    private Integer co2Ppm;
}
