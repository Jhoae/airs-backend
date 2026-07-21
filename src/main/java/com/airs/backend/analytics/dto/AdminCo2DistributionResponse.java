package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
@AllArgsConstructor
public class AdminCo2DistributionResponse {

    // 조회 권한을 가진 관리자의 캠퍼스 PK다.
    private Long campusId;
    // 화면 제목에 표시할 캠퍼스 이름이다.
    private String campusName;
    // 분포를 표시하는 기준 날짜다.
    private LocalDate date;
    // 설치 노드가 있는 공간만 센 전체 공간 수다.
    private int totalSpaceCount;
    // 데이터가 있는 공간들의 동등 가중 평균 CO2 값이다.
    private Integer averageCo2Ppm;
    // 좋음부터 데이터 없음까지의 CO2 구간별 항목이다.
    private List<AdminCo2DistributionItemResponse> distribution;
}
