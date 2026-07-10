package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
@AllArgsConstructor
public class AdminCo2TrendResponse {

    private Long campusId;
    private String campusName;
    private LocalDate date;
    private List<AdminCo2TrendPointResponse> todayTrend;
    private List<AdminCo2TrendPointResponse> yesterdayTrend;
}
