package com.airs.backend.node.dto.trend;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

// 선택한 단일 센서 지표의 차트 point를 API로 반환합니다.
@Getter
@AllArgsConstructor
public class AdminNodeSensorTrendPointResponse {

    // 집계 구간을 대표하는 UTC 시각입니다.
    private Instant timestamp;
    // temperature·humidity·co2 중 선택한 지표의 평균값입니다.
    private Double value;
}
