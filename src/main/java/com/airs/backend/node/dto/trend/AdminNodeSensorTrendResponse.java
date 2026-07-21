package com.airs.backend.node.dto.trend;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

// 노드 상세에서 선택한 센서 지표 하나의 기간별 추이를 반환합니다.
@Getter
@AllArgsConstructor
public class AdminNodeSensorTrendResponse {

    // 조회한 노드 ID입니다.
    private String nodeId;
    // temperature·humidity·co2 중 요청한 지표입니다.
    private String metric;
    // UI가 선택한 고정 기간입니다.
    private String period;
    // 실제 InfluxDB 조회 시작 시각입니다.
    private Instant from;
    // 실제 InfluxDB 조회 종료 시각입니다.
    private Instant to;
    // 그래프 point를 만든 집계 간격입니다.
    private String window;
    // 선택한 지표의 시간순 평균 point 목록입니다.
    private List<AdminNodeSensorTrendPointResponse> points;
}
