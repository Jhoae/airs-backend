package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminAnalyticsOverviewMetricsResponse {

    // 평가 결과가 있는 공간의 평균 comfort 점수다.
    private Integer averageComfortScore;
    // 환기 권장 단계인 공간 수다.
    private int ventilationRecommendedSpaceCount;
    // 즉시 환기가 필요한 공간 수다.
    private int ventilationNeededSpaceCount;
    // 냉난방 낭비가 의심되는 공간 수이며 데이터가 없으면 null이다.
    private Integer coolingWasteSuspectedCount;
    // 최근 telemetry가 정상적으로 수신된 노드 수다.
    private long onlineNodeCount;
    // 연결은 유지되지만 신호가 약한 노드 수다.
    private long weakNodeCount;
    // 일정 시간 telemetry가 수신되지 않은 노드 수다.
    private long offlineNodeCount;
    // 연결 상태를 분류할 데이터가 없는 노드 수다.
    private long unknownNodeCount;
    // 캠퍼스에 현재 설치된 전체 노드 수다.
    private int totalNodeCount;
    // 전체 노드 중 온라인 노드의 비율이다.
    private int onlineNodePercent;
}
