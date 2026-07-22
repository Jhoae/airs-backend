package com.airs.backend.node.metrics;

import com.airs.backend.node.cache.SensorTrendCacheStatus;
import com.airs.backend.node.dto.trend.AdminNodeCo2TrendPeriod;
import com.airs.backend.sensor.dto.SensorTrendMetric;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

// 노드 상세 센서 추이의 요청·Redis·InfluxDB 시간을 분리해 기록합니다.
@Slf4j
@Component
public class NodeSensorTrendMetrics {

    // 전체 서비스 호출 시간을 기록하는 Micrometer meter 이름입니다.
    private static final String REQUEST_TIMER = "airs.node.sensor.trend.request";
    // Redis 읽기 시간을 기록하는 Micrometer meter 이름입니다.
    private static final String REDIS_READ_TIMER = "airs.node.sensor.trend.redis.read";
    // Redis 쓰기 시간을 기록하는 Micrometer meter 이름입니다.
    private static final String REDIS_WRITE_TIMER = "airs.node.sensor.trend.redis.write";
    // InfluxDB 읽기와 point 조립 시간을 기록하는 Micrometer meter 이름입니다.
    private static final String INFLUX_LOAD_TIMER = "airs.node.sensor.trend.influx.load";
    // 5분 보고 전에 백분위 표본이 만료되지 않도록 분포 창을 10분으로 유지합니다.
    private static final Duration PERCENTILE_EXPIRY = Duration.ofMinutes(10);

    // 인메모리 Timer를 등록하고 snapshot을 읽는 Micrometer registry입니다.
    private final MeterRegistry meterRegistry;
    // 운영 환경에서 계측을 끌 수 있는 설정값입니다.
    private final boolean enabled;
    // 이전 보고 이후 새 표본이 있는 meter만 로그에 남기기 위한 count 저장소입니다.
    private final Map<Meter.Id, Long> lastReportedCounts = new ConcurrentHashMap<>();

    public NodeSensorTrendMetrics(
            MeterRegistry meterRegistry,
            @Value("${node.sensor-trend.metrics.enabled:true}") boolean enabled
    ) {
        // Spring Boot가 제공하는 registry를 보관합니다.
        this.meterRegistry = meterRegistry;
        // 환경 설정으로 계측 활성 여부를 보관합니다.
        this.enabled = enabled;
    }

    // 지정 구간의 경과 시간을 재기 시작합니다.
    public Timer.Sample start() {
        // 비활성화 상태에서도 호출 코드를 단순하게 유지하도록 가벼운 sample을 반환합니다.
        return Timer.start(meterRegistry);
    }

    // 서비스 내부 전체 요청 시간을 metric·period·cache 결과별로 기록합니다.
    public void recordRequest(
            Timer.Sample sample,
            SensorTrendMetric metric,
            AdminNodeCo2TrendPeriod period,
            SensorTrendCacheStatus cacheStatus,
            String outcome
    ) {
        // 계측을 끈 환경에서는 meter 등록과 기록을 하지 않습니다.
        if (!enabled) {
            return;
        }

        // 요청 결과를 저카디널리티 tag로 분리한 Timer에 경과 시간을 누적합니다.
        sample.stop(timer(
                REQUEST_TIMER,
                metricValue(metric),
                periodValue(period),
                cacheStatus == null ? "error" : cacheStatus.name().toLowerCase(),
                outcome
        ));
    }

    // InfluxDB raw·rollup 조회와 응답 point 조립 시간을 기록합니다.
    public void recordInfluxLoad(
            Timer.Sample sample,
            SensorTrendMetric metric,
            AdminNodeCo2TrendPeriod period,
            String strategy,
            String outcome
    ) {
        // 계측을 끈 환경에서는 meter 등록과 기록을 하지 않습니다.
        if (!enabled) {
            return;
        }

        // 선택 기간의 조회 전략과 성공 여부를 분리해 Influx 로드 시간을 누적합니다.
        sample.stop(timer(
                INFLUX_LOAD_TIMER,
                metricValue(metric),
                periodValue(period),
                strategy,
                outcome
        ));
    }

    // Redis 읽기 시간을 hit·miss·error 상태별로 기록합니다.
    public void recordRedisRead(
            Timer.Sample sample,
            SensorTrendMetric metric,
            AdminNodeCo2TrendPeriod period,
            String outcome
    ) {
        // 계측을 끈 환경에서는 meter 등록과 기록을 하지 않습니다.
        if (!enabled) {
            return;
        }

        // 같은 metric·period에서 cache hit 비율과 읽기 시간을 확인할 수 있게 기록합니다.
        sample.stop(timer(
                REDIS_READ_TIMER,
                metricValue(metric),
                periodValue(period),
                "redis",
                outcome
        ));
    }

    // Redis 쓰기 시간을 success·error 상태별로 기록합니다.
    public void recordRedisWrite(
            Timer.Sample sample,
            SensorTrendMetric metric,
            AdminNodeCo2TrendPeriod period,
            String outcome
    ) {
        // 계측을 끈 환경에서는 meter 등록과 기록을 하지 않습니다.
        if (!enabled) {
            return;
        }

        // cache miss 뒤 직렬화·저장에 든 시간을 별도로 누적합니다.
        sample.stop(timer(
                REDIS_WRITE_TIMER,
                metricValue(metric),
                periodValue(period),
                "redis",
                outcome
        ));
    }

    // 새 요청 표본이 있는 Timer의 평균·P50·P95를 5분마다 운영 로그에 남깁니다.
    @Scheduled(
            initialDelayString = "${node.sensor-trend.metrics.report-interval-ms:300000}",
            fixedDelayString = "${node.sensor-trend.metrics.report-interval-ms:300000}"
    )
    public void reportNewSamples() {
        // 계측을 끈 환경에서는 주기 로그도 남기지 않습니다.
        if (!enabled) {
            return;
        }

        // 이 컴포넌트가 등록한 Timer만 골라 snapshot을 순회합니다.
        meterRegistry.getMeters().stream()
                .filter(meter -> meter instanceof Timer)
                .filter(meter -> meter.getId().getName().startsWith("airs.node.sensor.trend."))
                .map(Timer.class::cast)
                .forEach(this::logTimerSnapshotWhenChanged);
    }

    // 이전 보고 뒤 새 표본이 생긴 Timer의 누적 count와 최근 10분 분포를 로그로 출력합니다.
    private void logTimerSnapshotWhenChanged(Timer timer) {
        // 현재 프로세스 기동 이후 누적된 표본 수를 읽습니다.
        long currentCount = timer.count();
        // 직전 보고 때의 표본 수를 읽습니다.
        long previousCount = lastReportedCounts.getOrDefault(timer.getId(), 0L);

        // 새 표본이 없으면 같은 통계를 반복 출력하지 않습니다.
        if (currentCount <= previousCount) {
            return;
        }

        // 최근 10분 분포에서 P50·P95가 비어 있을 때도 안전하게 NaN으로 기록합니다.
        Map<Double, Double> percentileMs = Arrays.stream(timer.takeSnapshot().percentileValues())
                .collect(Collectors.toMap(
                        value -> value.percentile(),
                        value -> value.value(TimeUnit.MILLISECONDS)
                ));

        // meter 이름·tag·count·평균·최대·백분위 시간을 한 줄로 기록합니다.
        log.info(
                "SENSOR_TREND_METRIC name={} tags={} count={} meanMs={} maxMs={} p50Ms={} p95Ms={}",
                timer.getId().getName(),
                timer.getId().getTags(),
                currentCount,
                formatMillis(timer.mean(TimeUnit.MILLISECONDS)),
                formatMillis(timer.max(TimeUnit.MILLISECONDS)),
                formatMillis(percentileMs.getOrDefault(0.5, Double.NaN)),
                formatMillis(percentileMs.getOrDefault(0.95, Double.NaN))
        );

        // 다음 주기에는 새로 추가된 표본이 있을 때만 다시 출력하도록 count를 갱신합니다.
        lastReportedCounts.put(timer.getId(), currentCount);
    }

    // metric이 파싱 전 오류인 경우에도 tag cardinality를 제한합니다.
    private String metricValue(SensorTrendMetric metric) {
        // 정상 요청은 API metric 값을 그대로 사용합니다.
        return metric == null ? "unknown" : metric.getApiValue();
    }

    // period가 파싱 전 오류인 경우에도 tag cardinality를 제한합니다.
    private String periodValue(AdminNodeCo2TrendPeriod period) {
        // 정상 요청은 API period 값을 그대로 사용합니다.
        return period == null ? "unknown" : period.getValue();
    }

    // 로그 숫자를 소수 셋째 자리까지 제한해 읽기 쉽게 만듭니다.
    private String formatMillis(double value) {
        // 아직 백분위 표본이 없으면 숫자를 꾸며내지 않습니다.
        if (Double.isNaN(value)) {
            return "n/a";
        }

        // 밀리초 값을 소수 셋째 자리까지 문자열로 변환합니다.
        return String.format("%.3f", value);
    }

    // metric·period·저장소·결과 tag를 가진 percentile Timer를 반환합니다.
    private Timer timer(
            String name,
            String metric,
            String period,
            String source,
            String outcome
    ) {
        // P50/P95는 같은 tag 조합의 최근 10분 표본을 기준으로 계산합니다.
        return Timer.builder(name)
                .description("노드 상세 선택형 센서 추이 성능 계측")
                .tags(
                        "metric", metric,
                        "period", period,
                        "source", source,
                        "outcome", outcome
                )
                // 로그 보고 주기보다 긴 분포 창을 유지해 백분위 값이 0으로 사라지지 않게 합니다.
                .distributionStatisticExpiry(PERCENTILE_EXPIRY)
                // 한 개의 10분 창만 사용해 최근 구간의 지연 분포를 읽습니다.
                .distributionStatisticBufferLength(1)
                .publishPercentiles(0.5, 0.95)
                .register(meterRegistry);
    }
}
