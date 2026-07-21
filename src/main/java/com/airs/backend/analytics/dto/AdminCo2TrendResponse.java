package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
@AllArgsConstructor
public class AdminCo2TrendResponse {

    // 추이를 조회한 캠퍼스 PK다.
    private Long campusId;
    // 추이를 조회한 캠퍼스 이름이다.
    private String campusName;
    // 오늘 추이 배열의 기준 날짜다.
    private LocalDate date;
    // 기준 날짜의 1시간 단위 평균 CO2 배열이다.
    private List<AdminCo2TrendPointResponse> todayTrend;
    // 기준 날짜 전날의 1시간 단위 평균 CO2 배열이다.
    private List<AdminCo2TrendPointResponse> yesterdayTrend;
}
