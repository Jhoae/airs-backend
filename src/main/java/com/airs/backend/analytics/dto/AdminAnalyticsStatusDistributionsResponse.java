package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class AdminAnalyticsStatusDistributionsResponse {

    // 설치된 노드가 있는 공간의 CO2 상태 분포다.
    private List<AdminAnalyticsDistributionItemResponse> co2;
    // 설치된 노드의 연결 상태 분포다.
    private List<AdminAnalyticsDistributionItemResponse> connection;
    // 설치된 노드가 있는 공간의 재실 상태 분포다.
    private List<AdminAnalyticsDistributionItemResponse> occupancy;
    // 설치된 노드의 Wi-Fi RSSI 상태 분포다.
    private List<AdminAnalyticsDistributionItemResponse> wifi;
}
