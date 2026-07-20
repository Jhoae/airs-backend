package com.airs.backend.analytics.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "analytics.cache")
public class AnalyticsCacheProperties {

    private boolean enabled = true;
    private long trendTodayTtlSeconds = 30;
    private long trendHistoryTtlSeconds = 1800;
    private String trendKeyPrefix = "airs:analytics:co2-trend:v1";
}
