package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
@AllArgsConstructor
public class AdminCo2AnalyticsResponse {

    private Long campusId;
    private String campusName;
    private LocalDate date;
    private int totalSpaceCount;
    private Integer averageCo2Ppm;
    private AdminCo2VentilationSummaryResponse ventilationSummary;
    private List<AdminCo2DistributionItemResponse> distribution;
    private List<AdminCo2TrendPointResponse> todayTrend;
    private List<AdminCo2TrendPointResponse> yesterdayTrend;
    private List<AdminCo2TopSpaceResponse> topSpaces;
}
