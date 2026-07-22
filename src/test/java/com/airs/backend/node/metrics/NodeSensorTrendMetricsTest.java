package com.airs.backend.node.metrics;

import com.airs.backend.node.cache.SensorTrendCacheStatus;
import com.airs.backend.node.dto.trend.AdminNodeCo2TrendPeriod;
import com.airs.backend.sensor.dto.SensorTrendMetric;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 노드 상세 센서 추이 계측기의 Timer 등록 규칙을 검증합니다.
class NodeSensorTrendMetricsTest {

    // 요청 계측이 cache 상태별 Timer와 백분위 설정을 등록하는지 확인합니다.
    @Test
    void recordRequest_should_register_percentile_timer_by_cache_status() throws InterruptedException {
        // 실제 운영과 같은 in-memory Micrometer registry를 준비합니다.
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        // 계측이 켜진 상태의 대상 객체를 생성합니다.
        NodeSensorTrendMetrics metrics = new NodeSensorTrendMetrics(meterRegistry, true);
        // 전체 요청 시간 측정을 시작합니다.
        Timer.Sample sample = metrics.start();
        // 0이 아닌 실행 시간을 만들기 위해 짧게 대기합니다.
        Thread.sleep(2);

        // co2 1개월 cache hit 요청을 기록합니다.
        metrics.recordRequest(
                sample,
                SensorTrendMetric.CO2,
                AdminNodeCo2TrendPeriod.ONE_MONTH,
                SensorTrendCacheStatus.HIT,
                "success"
        );

        // 동일 tag 조합의 Timer를 registry에서 읽습니다.
        Timer timer = meterRegistry.find("airs.node.sensor.trend.request")
                .tags(
                        "metric", "co2",
                        "period", "1mo",
                        "source", "hit",
                        "outcome", "success"
                )
                .timer();

        // 요청 표본이 정확히 한 번 기록됐는지 확인합니다.
        assertEquals(1, timer.count());
        // 측정 시간이 0보다 큰지 확인합니다.
        assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) > 0);
        // P50·P95를 위한 percentile 값 두 개가 등록됐는지 확인합니다.
        assertEquals(2, timer.takeSnapshot().percentileValues().length);
    }
}
