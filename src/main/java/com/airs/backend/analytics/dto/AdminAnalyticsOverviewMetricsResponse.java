package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminAnalyticsOverviewMetricsResponse {

    private Integer averageComfortScore;
    private int ventilationRecommendedSpaceCount;
    private int ventilationNeededSpaceCount;
    private Integer coolingWasteSuspectedCount;
    private long onlineNodeCount;
    private long weakNodeCount;
    private long offlineNodeCount;
    private long unknownNodeCount;
    private int totalNodeCount;
    private int onlineNodePercent;
}
