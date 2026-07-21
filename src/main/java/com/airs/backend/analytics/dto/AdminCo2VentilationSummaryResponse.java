package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminCo2VentilationSummaryResponse {

    // CO2가 1,000ppm 이하인 환기 양호 공간 수다.
    private int goodCount;
    // 전체 설치 공간 중 환기 양호 공간의 비율이다.
    private int goodPercent;
    // CO2가 1,001~1,500ppm인 환기 권장 공간 수다.
    private int recommendedCount;
    // 전체 설치 공간 중 환기 권장 공간의 비율이다.
    private int recommendedPercent;
    // CO2가 1,500ppm을 초과한 환기 필요 공간 수다.
    private int neededCount;
    // 전체 설치 공간 중 환기 필요 공간의 비율이다.
    private int neededPercent;
    // 최신 CO2 값이 없는 설치 공간 수다.
    private int noDataCount;
    // 전체 설치 공간 중 데이터 없는 공간의 비율이다.
    private int noDataPercent;
}
