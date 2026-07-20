package com.airs.backend.analytics.cache;

import com.airs.backend.analytics.config.AnalyticsCacheProperties;
import com.airs.backend.sensor.dto.Co2TrendItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdminCo2TrendCacheTest {

    @Test
    void getOrLoad_should_cache_by_campus_and_date() {
        AdminCo2TrendCache cache = new AdminCo2TrendCache(defaultProperties());
        AtomicInteger loadCount = new AtomicInteger();
        LocalDate date = LocalDate.of(2026, 7, 6);

        List<Co2TrendItem> first = cache.getOrLoad(1L, date, () -> loadTrend(loadCount));
        List<Co2TrendItem> second = cache.getOrLoad(1L, date, () -> loadTrend(loadCount));
        cache.getOrLoad(2L, date, () -> loadTrend(loadCount));
        cache.getOrLoad(1L, date.plusDays(1), () -> loadTrend(loadCount));

        assertEquals(first, second);
        assertEquals(3, loadCount.get());
    }

    private AnalyticsCacheProperties defaultProperties() {
        AnalyticsCacheProperties properties = new AnalyticsCacheProperties();
        properties.setTrendTodayTtlSeconds(30);
        properties.setTrendHistoryTtlSeconds(1800);
        properties.setTrendMaximumSize(200);
        return properties;
    }

    private List<Co2TrendItem> loadTrend(AtomicInteger loadCount) {
        loadCount.incrementAndGet();
        return List.of(new Co2TrendItem(Instant.parse("2026-07-06T01:00:00Z"), 842));
    }
}
