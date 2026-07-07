package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class AdminAnalyticsStatusDistributionsResponse {

    private List<AdminAnalyticsDistributionItemResponse> co2;
    private List<AdminAnalyticsDistributionItemResponse> connection;
    private List<AdminAnalyticsDistributionItemResponse> occupancy;
    private List<AdminAnalyticsDistributionItemResponse> wifi;
}
