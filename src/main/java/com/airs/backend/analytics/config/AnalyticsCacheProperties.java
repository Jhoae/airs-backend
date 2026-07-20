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

    private long trendTodayTtlSeconds = 30;
    private long trendHistoryTtlSeconds = 1800;
    private long trendMaximumSize = 200;
}
