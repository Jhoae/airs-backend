package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@AllArgsConstructor
public class AdminCo2SummaryResponse {

    // 조회한 관리자의 캠퍼스 PK다.
    private Long campusId;
    // 조회한 캠퍼스의 이름이다.
    private String campusName;
    // 요약을 표시할 기준 날짜다.
    private LocalDate date;
    // 설치 노드가 존재하는 공간 수다.
    private int totalSpaceCount;
    // 데이터가 있는 공간의 동등 가중 평균 CO2 값이다.
    private Integer averageCo2Ppm;
    // 환기 양호·권장·필요·데이터 없음의 집계값이다.
    private AdminCo2VentilationSummaryResponse ventilationSummary;
}
