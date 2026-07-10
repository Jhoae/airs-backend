package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminAnalyticsDistributionItemResponse {

    private String status;
    private String label;
    private String rangeLabel;
    private int count;
    private int percent;
    private String unit;
    private int totalCount;
}
