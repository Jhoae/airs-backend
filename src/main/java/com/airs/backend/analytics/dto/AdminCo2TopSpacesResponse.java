package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
@AllArgsConstructor
public class AdminCo2TopSpacesResponse {

    // 순위를 조회한 캠퍼스 PK다.
    private Long campusId;
    // 순위를 조회한 캠퍼스 이름이다.
    private String campusName;
    // 순위를 표시하는 기준 날짜다.
    private LocalDate date;
    // 높은 CO2 순으로 제한한 공간 목록이다.
    private List<AdminCo2TopSpaceResponse> topSpaces;
}
