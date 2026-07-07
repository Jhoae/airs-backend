package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
@AllArgsConstructor
public class AdminAnalyticsOverviewResponse {

    private Long campusId;
    private String campusName;
    private LocalDate date;
    private String summaryText;
    private AdminAnalyticsOverviewMetricsResponse metrics;
    private List<AdminCo2TrendPointResponse> co2AverageTrend;
    private AdminAnalyticsStatusDistributionsResponse statusDistributions;
    private List<AdminAnalyticsInsightResponse> topInsights;
}
