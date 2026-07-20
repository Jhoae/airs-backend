package com.airs.backend.analytics.cache;

import com.airs.backend.analytics.config.AnalyticsCacheProperties;
import com.airs.backend.sensor.dto.Co2TrendItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminCo2TrendCacheTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final Map<String, String> redisValues = new HashMap<>();

    @BeforeEach
    void setUp() {
        redisValues.clear();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenAnswer(invocation -> redisValues.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            redisValues.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void getOrLoad_should_reuse_redis_value_for_same_campus_and_date() {
        AdminCo2TrendCache cache = new AdminCo2TrendCache(redisTemplate, objectMapper(), defaultProperties());
        AtomicInteger loadCount = new AtomicInteger();
        LocalDate date = LocalDate.of(2026, 7, 6);

        List<Co2TrendItem> first = cache.getOrLoad(1L, date, () -> loadTrend(loadCount));
        List<Co2TrendItem> second = cache.getOrLoad(1L, date, () -> loadTrend(loadCount));
        cache.getOrLoad(2L, date, () -> loadTrend(loadCount));
        cache.getOrLoad(1L, date.plusDays(1), () -> loadTrend(loadCount));

        assertEquals(first.getFirst().getTimestamp(), second.getFirst().getTimestamp());
        assertEquals(first.getFirst().getCo2Ppm(), second.getFirst().getCo2Ppm());
        assertEquals(3, loadCount.get());
        verify(valueOperations, times(3)).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void getOrLoad_should_fall_back_to_loader_when_redis_is_unavailable() {
        when(valueOperations.get(anyString())).thenThrow(new RedisConnectionFailureException("Redis unavailable"));
        AdminCo2TrendCache cache = new AdminCo2TrendCache(redisTemplate, objectMapper(), defaultProperties());
        AtomicInteger loadCount = new AtomicInteger();

        List<Co2TrendItem> trend = cache.getOrLoad(1L, LocalDate.of(2026, 7, 6), () -> loadTrend(loadCount));

        assertEquals(842, trend.getFirst().getCo2Ppm());
        assertEquals(1, loadCount.get());
    }

    private AnalyticsCacheProperties defaultProperties() {
        AnalyticsCacheProperties properties = new AnalyticsCacheProperties();
        properties.setEnabled(true);
        properties.setTrendTodayTtlSeconds(30);
        properties.setTrendHistoryTtlSeconds(1800);
        properties.setTrendKeyPrefix("airs:test:co2-trend:v1");
        return properties;
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private List<Co2TrendItem> loadTrend(AtomicInteger loadCount) {
        loadCount.incrementAndGet();
        return List.of(new Co2TrendItem(Instant.parse("2026-07-06T01:00:00Z"), 842));
    }
}
