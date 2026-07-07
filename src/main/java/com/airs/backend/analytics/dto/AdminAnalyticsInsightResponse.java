package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminAnalyticsInsightResponse {

    private String type;
    private String severity;
    private String title;
    private String message;
    private String nodeId;
    private Long spaceId;
    private String spaceCode;
    private String spaceName;
    private String buildingName;
}
