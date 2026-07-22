package com.airs.backend.node.cache;

import com.airs.backend.node.dto.trend.AdminNodeCo2TrendPeriod;
import com.airs.backend.node.dto.trend.AdminNodeSensorTrendPointResponse;
import com.airs.backend.node.dto.trend.AdminNodeSensorTrendResponse;
import com.airs.backend.node.metrics.NodeSensorTrendMetrics;
import com.airs.backend.sensor.dto.SensorTrendMetric;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
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

class AdminNodeSensorTrendCacheTest {

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
    void getOrLoad_should_reuse_response_only_for_same_node_metric_and_period() {
        AdminNodeSensorTrendCache cache = new AdminNodeSensorTrendCache(redisTemplate, objectMapper(), defaultProperties(), metrics());
        AtomicInteger loadCount = new AtomicInteger();

        SensorTrendCacheLoadResult first = cache.getOrLoad(
                "node_01",
                SensorTrendMetric.TEMPERATURE,
                AdminNodeCo2TrendPeriod.ONE_MONTH,
                () -> loadTrend(loadCount, "temperature", "1mo")
        );
        SensorTrendCacheLoadResult second = cache.getOrLoad(
                "node_01",
                SensorTrendMetric.TEMPERATURE,
                AdminNodeCo2TrendPeriod.ONE_MONTH,
                () -> loadTrend(loadCount, "temperature", "1mo")
        );
        cache.getOrLoad(
                "node_01",
                SensorTrendMetric.HUMIDITY,
                AdminNodeCo2TrendPeriod.ONE_MONTH,
                () -> loadTrend(loadCount, "humidity", "1mo")
        );
        cache.getOrLoad(
                "node_01",
                SensorTrendMetric.TEMPERATURE,
                AdminNodeCo2TrendPeriod.FIVE_DAYS,
                () -> loadTrend(loadCount, "temperature", "5d")
        );

        assertEquals(first.response().getFrom(), second.response().getFrom());
        assertEquals(24.3, second.response().getPoints().getFirst().getValue());
        assertEquals(SensorTrendCacheStatus.MISS, first.cacheStatus());
        assertEquals(SensorTrendCacheStatus.HIT, second.cacheStatus());
        assertEquals(3, loadCount.get());
        verify(valueOperations, times(3)).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void getOrLoad_should_return_fresh_response_when_redis_is_unavailable() {
        when(valueOperations.get(anyString())).thenThrow(new RedisConnectionFailureException("Redis unavailable"));
        AdminNodeSensorTrendCache cache = new AdminNodeSensorTrendCache(redisTemplate, objectMapper(), defaultProperties(), metrics());
        AtomicInteger loadCount = new AtomicInteger();

        SensorTrendCacheLoadResult result = cache.getOrLoad(
                "node_01",
                SensorTrendMetric.CO2,
                AdminNodeCo2TrendPeriod.ONE_DAY,
                () -> loadTrend(loadCount, "co2", "1d")
        );

        assertEquals("co2", result.response().getMetric());
        assertEquals(SensorTrendCacheStatus.MISS, result.cacheStatus());
        assertEquals(1, loadCount.get());
    }

    private NodeSensorTrendCacheProperties defaultProperties() {
        NodeSensorTrendCacheProperties properties = new NodeSensorTrendCacheProperties();
        properties.setEnabled(true);
        properties.setTtlSeconds(30);
        properties.setKeyPrefix("airs:test:node:sensor-trend:v1");
        return properties;
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private NodeSensorTrendMetrics metrics() {
        return new NodeSensorTrendMetrics(new SimpleMeterRegistry(), true);
    }

    private AdminNodeSensorTrendResponse loadTrend(AtomicInteger loadCount, String metric, String period) {
        loadCount.incrementAndGet();
        Instant from = Instant.parse("2026-07-21T00:00:00Z");
        Instant to = Instant.parse("2026-07-21T01:00:00Z");
        return new AdminNodeSensorTrendResponse(
                "node_01",
                metric,
                period,
                from,
                to,
                "1h",
                List.of(new AdminNodeSensorTrendPointResponse(from, 24.3))
        );
    }
}
