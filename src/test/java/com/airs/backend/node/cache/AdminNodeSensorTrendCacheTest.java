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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
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
        // 기본 요청은 첫 요청이 되어 원본 조회를 한 번 수행하도록 설정합니다.
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
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
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("Redis unavailable"));
        AdminNodeSensorTrendCache cache = new AdminNodeSensorTrendCache(redisTemplate, objectMapper(), defaultProperties(), metrics());
        AtomicInteger loadCount = new AtomicInteger();

        SensorTrendCacheLoadResult result = cache.getOrLoad(
                "node_01",
                SensorTrendMetric.CO2,
                AdminNodeCo2TrendPeriod.ONE_DAY,
                () -> loadTrend(loadCount, "co2", "1d")
        );

        assertEquals("co2", result.response().getMetric());
        assertEquals(SensorTrendCacheStatus.MISS_TIMEOUT_FALLBACK, result.cacheStatus());
        assertEquals(1, loadCount.get());
    }

    @Test
    void getOrLoad_should_reuse_leader_result_when_same_cache_miss_is_already_loading() throws Exception {
        AdminNodeSensorTrendResponse firstRequestResponse = loadTrend(new AtomicInteger(), "temperature", "1mo");
        String firstRequestJson = cacheEnvelopeJson(firstRequestResponse, Instant.now());
        AtomicInteger readCount = new AtomicInteger();

        // 첫 읽기는 캐시 미스이고, 첫 요청이 저장했다고 가정한 두 번째 읽기부터 결과를 돌려줍니다.
        when(valueOperations.get(anyString())).thenAnswer(invocation ->
                readCount.incrementAndGet() == 1 ? null : firstRequestJson
        );
        // 이미 다른 요청이 잠금을 얻은 상황을 만들어 대기 요청 경로를 검증합니다.
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        AdminNodeSensorTrendCache cache = new AdminNodeSensorTrendCache(redisTemplate, objectMapper(), waitingProperties(), metrics());
        AtomicInteger followerLoadCount = new AtomicInteger();

        SensorTrendCacheLoadResult result = cache.getOrLoad(
                "node_01",
                SensorTrendMetric.TEMPERATURE,
                AdminNodeCo2TrendPeriod.ONE_MONTH,
                () -> loadTrend(followerLoadCount, "temperature", "1mo")
        );

        assertEquals(SensorTrendCacheStatus.HIT_AFTER_WAIT, result.cacheStatus());
        assertEquals(24.3, result.response().getPoints().getFirst().getValue());
        assertEquals(0, followerLoadCount.get());
    }

    @Test
    void getOrLoad_should_return_stale_response_while_another_request_refreshes_it() throws Exception {
        AdminNodeSensorTrendResponse staleResponse = loadTrend(new AtomicInteger(), "temperature", "1mo");
        redisValues.put(
                "airs:test:node:sensor-trend:v1:node:node_01:metric:temperature:period:1mo",
                cacheEnvelopeJson(staleResponse, Instant.now().minusSeconds(31))
        );
        // 다른 요청이 갱신 잠금을 가진 상태를 만들어 stale 응답을 즉시 반환하는지 검증합니다.
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        AdminNodeSensorTrendCache cache = new AdminNodeSensorTrendCache(redisTemplate, objectMapper(), defaultProperties(), metrics());
        AtomicInteger followerLoadCount = new AtomicInteger();

        SensorTrendCacheLoadResult result = cache.getOrLoad(
                "node_01",
                SensorTrendMetric.TEMPERATURE,
                AdminNodeCo2TrendPeriod.ONE_MONTH,
                () -> loadTrend(followerLoadCount, "temperature", "1mo")
        );

        assertEquals(SensorTrendCacheStatus.STALE_HIT, result.cacheStatus());
        assertEquals(24.3, result.response().getPoints().getFirst().getValue());
        assertEquals(0, followerLoadCount.get());
    }

    @Test
    void getOrLoad_should_return_stale_response_when_only_load_lock_is_unavailable() throws Exception {
        AdminNodeSensorTrendResponse staleResponse = loadTrend(new AtomicInteger(), "temperature", "1mo");
        redisValues.put(
                "airs:test:node:sensor-trend:v1:node:node_01:metric:temperature:period:1mo",
                cacheEnvelopeJson(staleResponse, Instant.now().minusSeconds(31))
        );
        // 값 읽기는 성공했지만 잠금 명령만 일시 실패한 상황을 만들어 InfluxDB 우회를 막는지 검증합니다.
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("Redis lock unavailable"));

        AdminNodeSensorTrendCache cache = new AdminNodeSensorTrendCache(redisTemplate, objectMapper(), defaultProperties(), metrics());
        AtomicInteger fallbackLoadCount = new AtomicInteger();

        SensorTrendCacheLoadResult result = cache.getOrLoad(
                "node_01",
                SensorTrendMetric.TEMPERATURE,
                AdminNodeCo2TrendPeriod.ONE_MONTH,
                () -> loadTrend(fallbackLoadCount, "temperature", "1mo")
        );

        assertEquals(SensorTrendCacheStatus.STALE_HIT, result.cacheStatus());
        assertEquals(24.3, result.response().getPoints().getFirst().getValue());
        assertEquals(0, fallbackLoadCount.get());
    }

    @Test
    void getOrLoad_should_treat_legacy_cache_json_as_stale_during_rollout() throws Exception {
        AdminNodeSensorTrendResponse legacyResponse = loadTrend(new AtomicInteger(), "temperature", "1mo");
        redisValues.put(
                "airs:test:node:sensor-trend:v1:node:node_01:metric:temperature:period:1mo",
                objectMapper().writeValueAsString(legacyResponse)
        );
        // 다른 요청이 새 envelope 형식으로 갱신 중이면 이전 형식을 즉시 재사용합니다.
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        AdminNodeSensorTrendCache cache = new AdminNodeSensorTrendCache(redisTemplate, objectMapper(), defaultProperties(), metrics());
        AtomicInteger followerLoadCount = new AtomicInteger();

        SensorTrendCacheLoadResult result = cache.getOrLoad(
                "node_01",
                SensorTrendMetric.TEMPERATURE,
                AdminNodeCo2TrendPeriod.ONE_MONTH,
                () -> loadTrend(followerLoadCount, "temperature", "1mo")
        );

        assertEquals(SensorTrendCacheStatus.STALE_HIT, result.cacheStatus());
        assertEquals(24.3, result.response().getPoints().getFirst().getValue());
        assertEquals(0, followerLoadCount.get());
    }

    @Test
    void getOrLoad_should_renew_leader_lock_while_loader_is_slower_than_renew_interval() throws Exception {
        NodeSensorTrendCacheProperties properties = defaultProperties();
        properties.setLoadLockTtlSeconds(1);
        properties.setLoadLockRenewIntervalMillis(100);
        when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(1L);

        AdminNodeSensorTrendCache cache = new AdminNodeSensorTrendCache(redisTemplate, objectMapper(), properties, metrics());
        AtomicInteger loadCount = new AtomicInteger();

        SensorTrendCacheLoadResult result = cache.getOrLoad(
                "node_01",
                SensorTrendMetric.TEMPERATURE,
                AdminNodeCo2TrendPeriod.ONE_MONTH,
                () -> {
                    sleep(1_200);
                    return loadTrend(loadCount, "temperature", "1mo");
                }
        );

        assertEquals(SensorTrendCacheStatus.MISS, result.cacheStatus());
        assertEquals(1, loadCount.get());
        verify(redisTemplate, atLeast(2)).execute(any(), anyList(), any(), any());
    }

    private NodeSensorTrendCacheProperties defaultProperties() {
        NodeSensorTrendCacheProperties properties = new NodeSensorTrendCacheProperties();
        properties.setEnabled(true);
        properties.setTtlSeconds(30);
        properties.setStaleTtlSeconds(60);
        properties.setKeyPrefix("airs:test:node:sensor-trend:v1");
        properties.setLoadLockTtlSeconds(10);
        properties.setLoadLockRenewIntervalMillis(2500);
        properties.setLoadWaitMillis(1000);
        properties.setLoadPollMillis(1);
        return properties;
    }

    private NodeSensorTrendCacheProperties waitingProperties() {
        NodeSensorTrendCacheProperties properties = defaultProperties();
        properties.setLoadWaitMillis(50);
        return properties;
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private NodeSensorTrendMetrics metrics() {
        return new NodeSensorTrendMetrics(new SimpleMeterRegistry(), true);
    }

    private String cacheEnvelopeJson(AdminNodeSensorTrendResponse response, Instant cachedAt) throws Exception {
        return objectMapper().writeValueAsString(Map.of(
                "cachedAtEpochMilli", cachedAt.toEpochMilli(),
                "response", response
        ));
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

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("테스트 대기 중 인터럽트가 발생했습니다.", exception);
        }
    }
}
