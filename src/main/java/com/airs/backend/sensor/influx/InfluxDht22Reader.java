package com.airs.backend.sensor.influx;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.airs.backend.sensor.config.InfluxProperties;
import com.airs.backend.sensor.dto.AiSensorTrendData;
import com.airs.backend.sensor.dto.Co2TrendItem;
import com.airs.backend.sensor.dto.DailyDht22SummaryResponse;
import com.airs.backend.sensor.dto.Dht22MeasurementItem;
import com.airs.backend.sensor.dto.SensorTrendItem;
import com.airs.backend.sensor.dto.SensorTrendMetric;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;

import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// InfluxDB raw·rollup 데이터를 읽어 센서·분석 서비스에 제공하는 컴포넌트입니다.
@Component
// InfluxDB 설정을 생성자로 주입합니다.
@RequiredArgsConstructor
// rollup fallback과 조회 실패 원인을 기록합니다.
@Slf4j
public class InfluxDht22Reader {

    // 일 단위 CO2 평균을 저장한 rollup measurement 이름입니다.
    private static final String DAILY_CO2_ROLLUP_MEASUREMENT = "sensor_rollup_1d";
    // 온도·습도·CO2 공통 일 rollup measurement 이름입니다.
    private static final String DAILY_SENSOR_ROLLUP_MEASUREMENT = "sensor_rollup_1d";
    // 시간 rollup을 6시간 그래프 point로 다시 묶는 기간입니다.
    private static final int SIX_HOUR_WINDOW_HOURS = 6;

    // bucket·measurement·node tag 설정을 사용해 Flux를 조립합니다.
    private final InfluxProperties influxProperties;
    // 애플리케이션 생명주기 동안 유지할 InfluxDB 조회 연결입니다.
    private InfluxDBClient influxDBClient;
    // Flux 문자열을 실행하는 InfluxDB query API입니다.
    private QueryApi queryApi;

    // Spring bean 생성 후 InfluxDB 조회 연결을 초기화합니다.
    @PostConstruct
    public void init() {
        // URL이 없으면 InfluxDB 서버에 연결할 수 없습니다.
        if (influxProperties.getUrl() == null || influxProperties.getUrl().isBlank()) {
            throw new IllegalStateException("influx.url 설정이 비어 있습니다.");
        }

        // 토큰이 없으면 조회 권한을 인증할 수 없습니다.
        if (influxProperties.getToken() == null || influxProperties.getToken().isBlank()) {
            throw new IllegalStateException("influx.token 설정이 비어 있습니다.");
        }

        // organization이 없으면 Flux 실행 범위를 결정할 수 없습니다.
        if (influxProperties.getOrg() == null || influxProperties.getOrg().isBlank()) {
            throw new IllegalStateException("influx.org 설정이 비어 있습니다.");
        }

        // raw bucket이 없으면 센서 원본 시계열을 조회할 수 없습니다.
        if (influxProperties.getBucket() == null || influxProperties.getBucket().isBlank()) {
            throw new IllegalStateException("influx.bucket 설정이 비어 있습니다.");
        }

        // measurement가 없으면 센서 데이터 종류를 필터링할 수 없습니다.
        if (influxProperties.getMeasurement() == null || influxProperties.getMeasurement().isBlank()) {
            throw new IllegalStateException("influx.measurement 설정이 비어 있습니다.");
        }

        // node tag 이름이 없으면 노드별 Flux 조건을 만들 수 없습니다.
        if (influxProperties.getNodeIdTag() == null || influxProperties.getNodeIdTag().isBlank()) {
            throw new IllegalStateException("influx.node-id-tag 설정이 비어 있습니다.");
        }

        // 설정된 URL·토큰·organization·bucket으로 InfluxDB 클라이언트를 생성합니다.
        influxDBClient = InfluxDBClientFactory.create(
                influxProperties.getUrl(),
                influxProperties.getToken().toCharArray(),
                influxProperties.getOrg(),
                influxProperties.getBucket()
        );
        // Flux를 동기 실행할 query API를 준비합니다.
        queryApi = influxDBClient.getQueryApi();
    }


    // 지정한 UTC 날짜의 노드 온도·습도 일간 요약을 계산합니다.
    public DailyDht22SummaryResponse readDailySummary(String nodeId, LocalDate date) {
        // 노드 식별자가 없으면 어느 시계열을 조회할지 알 수 없습니다.
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId가 비어 있습니다.");
        }

        // 날짜가 없으면 일간 조회 범위를 계산할 수 없습니다.
        if (date == null) {
            throw new IllegalArgumentException("date가 비어 있습니다.");
        }

        // 요청 날짜의 UTC 자정부터 조회를 시작합니다.
        Instant from = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        // 다음 UTC 자정 전까지를 조회 범위 끝으로 사용합니다.
        Instant to = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        // 같은 기간의 원본 온도·습도·CO2 측정값을 읽습니다.
        List<Dht22MeasurementItem> measurements = queryMeasurements(nodeId, from, to);

        // 측정값이 없으면 모든 통계 필드를 null로 둔 빈 요약을 반환합니다.
        if (measurements.isEmpty()) {
            return new DailyDht22SummaryResponse(
                    nodeId,
                    date,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }

        // 하루 중 가장 높은 온도와 해당 시각을 찾습니다.
        Dht22MeasurementItem peakTemperatureItem = measurements.stream()
                .max(Comparator.comparing(item -> item.getTemperature()))
                .orElseThrow();

        // 하루 중 가장 낮은 온도와 해당 시각을 찾습니다.
        Dht22MeasurementItem minTemperatureItem = measurements.stream()
                .min(Comparator.comparing(item -> item.getTemperature()))
                .orElseThrow();

        // 하루 중 가장 높은 습도와 해당 시각을 찾습니다.
        Dht22MeasurementItem peakHumidityItem = measurements.stream()
                .max(Comparator.comparing(item -> item.getHumidity()))
                .orElseThrow();

        // 하루 중 가장 낮은 습도와 해당 시각을 찾습니다.
        Dht22MeasurementItem minHumidityItem = measurements.stream()
                .min(Comparator.comparing(item -> item.getHumidity()))
                .orElseThrow();

        // null을 제외한 모든 온도 측정값의 산술 평균을 계산합니다.
        double averageTemperature = measurements.stream()
                .map(item -> item.getTemperature())
                .filter(value -> value != null)
                .mapToDouble(value -> value.doubleValue())
                .average()
                .orElseThrow();

        // null을 제외한 모든 습도 측정값의 산술 평균을 계산합니다.
        double averageHumidity = measurements.stream()
                .map(item -> item.getHumidity())
                .filter(value -> value != null)
                .mapToDouble(value -> value.doubleValue())
                .average()
                .orElseThrow();

        // 계산한 극값·평균·시각을 일간 요약 응답으로 반환합니다.
        return new DailyDht22SummaryResponse(
                nodeId,
                date,
                peakTemperatureItem.getTemperature(),
                peakTemperatureItem.getTimestamp(),
                averageTemperature,
                minTemperatureItem.getTemperature(),
                minTemperatureItem.getTimestamp(),
                peakHumidityItem.getHumidity(),
                peakHumidityItem.getTimestamp(),
                averageHumidity,
                minHumidityItem.getHumidity(),
                minHumidityItem.getTimestamp()
        );
    }
    // 노드 상세 화면이 사용할 기간별 CO2 평균 point를 raw bucket에서 읽습니다.
    public List<Co2TrendItem> readCo2Trend(String nodeId, Instant from, Instant to, String window) {
        // node·기간·Flux window 형식을 조회 전에 검증합니다.
        validateCo2TrendRequest(nodeId, from, to, window);

        // 초기화되지 않은 query API로 조회하지 않게 차단합니다.
        if (queryApi == null) {
            throw new IllegalStateException("InfluxDB queryApi가 초기화되지 않았습니다.");
        }

        // 노드별 CO2 raw 값을 window 단위 평균 point로 만드는 Flux를 조립합니다.
        String query = """
                from(bucket: "%s")
                  |> range(start: time(v: "%s"), stop: time(v: "%s"))
                  |> filter(fn: (r) => r._measurement == "%s")
                  |> filter(fn: (r) => r.%s == "%s")
                  |> filter(fn: (r) => r._field == "co2_ppm")
                  |> aggregateWindow(every: %s, fn: mean, createEmpty: false)
                  |> sort(columns: ["_time"])
                """.formatted(
                influxProperties.getBucket(),
                from,
                to,
                influxProperties.getMeasurement(),
                influxProperties.getNodeIdTag(),
                nodeId.replace("\"", "\\\""),
                window
        );

        // 조립한 Flux를 organization 범위에서 실행합니다.
        List<FluxTable> tables = queryApi.query(query, influxProperties.getOrg());

        // 모든 Flux table record를 화면용 시각·ppm DTO 목록으로 변환합니다.
        return tables.stream()
                .flatMap(table -> table.getRecords().stream())
                .map(record -> toCo2TrendItem(record))
                .toList();
    }

    // 노드 상세가 선택한 온도·습도·CO2 한 지표의 raw 평균 point를 읽습니다.
    public List<SensorTrendItem> readSensorTrend(
            SensorTrendMetric metric,
            String nodeId,
            Instant from,
            Instant to,
            String window
    ) {
        // metric·node·기간·집계 간격이 모두 유효한지 조회 전에 검증합니다.
        validateSensorTrendRequest(metric, nodeId, from, to, window);

        // 초기화되지 않은 query API로 조회하지 않게 차단합니다.
        requireQueryApi();

        // 선택한 raw field를 window 평균 point로 만드는 Flux를 조립합니다.
        String query = """
                from(bucket: "%s")
                  |> range(start: time(v: "%s"), stop: time(v: "%s"))
                  |> filter(fn: (r) => r._measurement == "%s")
                  |> filter(fn: (r) => r.%s == "%s")
                  |> filter(fn: (r) => r._field == "%s")
                  |> aggregateWindow(every: %s, fn: mean, createEmpty: false)
                  |> sort(columns: ["_time"])
                """.formatted(
                influxProperties.getBucket(),
                from,
                to,
                influxProperties.getMeasurement(),
                influxProperties.getNodeIdTag(),
                escapeFluxString(nodeId),
                metric.getRawField(),
                window
        );

        // Flux 결과를 지표와 무관한 시각·평균값 point 목록으로 변환합니다.
        return queryApi.query(query, influxProperties.getOrg()).stream()
                .flatMap(table -> table.getRecords().stream())
                .map(record -> toSensorTrendItem(record, metric))
                .toList();
    }

    // 6개월·1년 지표 추이를 일 rollup 우선으로 읽고 공백은 raw로 보완합니다.
    public List<SensorTrendItem> readSensorTrendWithDailyRollup(
            SensorTrendMetric metric,
            String nodeId,
            Instant from,
            Instant to
    ) {
        // 일 단위 그래프 규칙으로 요청 값을 검증합니다.
        validateSensorTrendRequest(metric, nodeId, from, to, "1d");

        // rollup 실패는 화면 오류 대신 raw 일 평균으로 대체합니다.
        try {
            // 완료된 날짜의 지표 평균을 rollup bucket에서 읽습니다.
            List<SensorTrendItem> rollupTrend = readDailySensorRollup(metric, nodeId, from, to);

            // 아직 해당 metric의 backfill이 끝나지 않았으면 raw 조회가 정확합니다.
            if (rollupTrend.isEmpty()) {
                return readSensorTrend(metric, nodeId, from, to, "1d");
            }

            // 날짜 중간의 rollup이 하나라도 비면 부분 결과를 섞지 않고 raw 전체를 사용합니다.
            if (hasDailySensorRollupGap(rollupTrend)) {
                return readSensorTrend(metric, nodeId, from, to, "1d");
            }

            // 첫 rollup point 이전 구간은 raw 일 평균으로 보완합니다.
            Instant firstRollupTime = rollupTrend.get(0).getTimestamp();
            List<SensorTrendItem> rawBeforeRollup = from.isBefore(firstRollupTime)
                    ? readSensorTrend(metric, nodeId, from, firstRollupTime, "1d")
                    : List.of();

            // 마지막 rollup point 이후의 진행 중 날짜는 raw 일 평균으로 보완합니다.
            Instant lastRollupTime = rollupTrend.get(rollupTrend.size() - 1).getTimestamp();
            List<SensorTrendItem> rawAfterRollup = lastRollupTime.isBefore(to)
                    ? readSensorTrend(metric, nodeId, lastRollupTime, to, "1d")
                    : List.of();

            // rollup을 우선값으로 넣어 시각순의 중복 없는 그래프 point를 반환합니다.
            return mergeSensorTrendPoints(rawBeforeRollup, rawAfterRollup, rollupTrend);
        } catch (RuntimeException exception) {
            // 개별 metric rollup 오류는 raw fallback 사유를 남기고 복구합니다.
            log.warn(
                    "노드 센서 일 rollup 조회에 실패해 raw 데이터를 사용합니다. nodeId={}, metric={}, reason={}",
                    nodeId,
                    metric.getApiValue(),
                    exception.getMessage()
            );
            // raw sensor_data를 일 평균으로 집계해 정확한 fallback 응답을 반환합니다.
            return readSensorTrend(metric, nodeId, from, to, "1d");
        }
    }

    // 5일·1개월 지표 추이를 1시간 rollup 우선으로 읽고 경계만 raw로 보완합니다.
    public List<SensorTrendItem> readSensorTrendWithHourlyRollup(
            SensorTrendMetric metric,
            String nodeId,
            Instant from,
            Instant to,
            String window
    ) {
        // 요청 지표·기간·집계 간격을 먼저 검증합니다.
        validateSensorTrendRequest(metric, nodeId, from, to, window);

        // 현재 시간 rollup은 5일 1시간 또는 1개월 6시간 그래프만 지원합니다.
        if (!"1h".equals(window) && !"6h".equals(window)) {
            throw new IllegalArgumentException("시간 rollup은 1h 또는 6h window에만 사용할 수 있습니다.");
        }

        // rollup 오류가 발생해도 기존 raw 그래프를 계속 제공하도록 처리합니다.
        try {
            // 5일은 완료된 1시간 평균을 그대로 사용합니다.
            if ("1h".equals(window)) {
                return readHourlySensorTrendWithRawFallback(metric, nodeId, from, to);
            }

            // 1개월은 시간 평균을 count 가중 6시간 평균으로 재집계합니다.
            return readSixHourlySensorTrendWithRawFallback(metric, nodeId, from, to);
        } catch (RuntimeException exception) {
            // rollup 장애가 노드 상세 화면의 실패로 번지지 않게 raw로 복구합니다.
            log.warn(
                    "노드 센서 시간 rollup 조회에 실패해 raw 데이터를 사용합니다. nodeId={}, metric={}, reason={}",
                    nodeId,
                    metric.getApiValue(),
                    exception.getMessage()
            );
            // 기존 raw 집계와 같은 window로 fallback 응답을 반환합니다.
            return readSensorTrend(metric, nodeId, from, to, window);
        }
    }

    // 5일 화면의 완료 1시간 rollup과 양 끝 raw 구간을 합칩니다.
    private List<SensorTrendItem> readHourlySensorTrendWithRawFallback(
            SensorTrendMetric metric,
            String nodeId,
            Instant from,
            Instant to
    ) {
        // 요청 범위에 온전히 포함되는 첫 1시간의 종료 시각을 계산합니다.
        Instant firstCompleteHourEnd = firstCompleteWindowEnd(from, 1);
        // 요청 범위에 온전히 포함되는 마지막 1시간의 종료 시각을 계산합니다.
        Instant lastCompleteHourEnd = floorToWindow(to, 1);

        // 완결된 1시간이 없으면 raw만 읽는 편이 더 정확합니다.
        if (firstCompleteHourEnd.isAfter(lastCompleteHourEnd)) {
            return readSensorTrend(metric, nodeId, from, to, "1h");
        }

        // 완료된 시간대의 평균과 count rollup을 함께 읽습니다.
        List<HourlySensorRollupItem> hourlyRollups = readHourlySensorRollups(metric, nodeId, from, to);
        // 필요한 시간대 하나라도 빠지면 부분 결과를 섞지 않고 raw 전체로 fallback합니다.
        if (!hasCompleteHourlySensorCoverage(hourlyRollups, firstCompleteHourEnd, lastCompleteHourEnd)) {
            return readSensorTrend(metric, nodeId, from, to, "1h");
        }

        // 완결된 시간대만 정렬해 공식 rollup point로 선택합니다.
        List<HourlySensorRollupItem> completeRollups = selectHourlySensorRollups(
                hourlyRollups,
                firstCompleteHourEnd,
                lastCompleteHourEnd
        );
        // 첫 rollup이 실제로 다루는 구간의 시작 시각을 계산합니다.
        Instant firstRollupStart = firstCompleteHourEnd.minus(1, ChronoUnit.HOURS);

        // 첫 완결 시간 전의 불완전 구간만 raw로 보완합니다.
        List<SensorTrendItem> rawBeforeRollup = from.isBefore(firstRollupStart)
                ? readSensorTrend(metric, nodeId, from, firstRollupStart, "1h")
                : List.of();
        // 마지막 완결 시간 이후의 진행 중 구간만 raw로 보완합니다.
        List<SensorTrendItem> rawAfterRollup = lastCompleteHourEnd.isBefore(to)
                ? readSensorTrend(metric, nodeId, lastCompleteHourEnd, to, "1h")
                : List.of();

        // 완료된 1시간은 rollup, 양 끝은 raw로 합쳐 반환합니다.
        return mergeSensorTrendPoints(
                rawBeforeRollup,
                rawAfterRollup,
                completeRollups.stream().map(this::toSensorTrendItem).toList()
        );
    }

    // 1개월 화면의 완료 6시간 구간을 시간 rollup의 count 가중 평균으로 만듭니다.
    private List<SensorTrendItem> readSixHourlySensorTrendWithRawFallback(
            SensorTrendMetric metric,
            String nodeId,
            Instant from,
            Instant to
    ) {
        // 요청 범위에 온전히 포함되는 첫 6시간 종료 시각을 계산합니다.
        Instant firstCompleteSixHourEnd = firstCompleteWindowEnd(from, SIX_HOUR_WINDOW_HOURS);
        // 요청 범위에 온전히 포함되는 마지막 6시간 종료 시각을 계산합니다.
        Instant lastCompleteSixHourEnd = floorToWindow(to, SIX_HOUR_WINDOW_HOURS);

        // 완결된 6시간 구간이 없으면 raw 6시간 평균으로 응답합니다.
        if (firstCompleteSixHourEnd.isAfter(lastCompleteSixHourEnd)) {
            return readSensorTrend(metric, nodeId, from, to, "6h");
        }

        // 6시간 평균을 만들 모든 1시간 rollup을 읽습니다.
        List<HourlySensorRollupItem> hourlyRollups = readHourlySensorRollups(metric, nodeId, from, to);
        // 첫 6시간 window에 필요한 첫 1시간 종료 시각을 계산합니다.
        Instant firstRequiredHourEnd = firstCompleteSixHourEnd.minus(SIX_HOUR_WINDOW_HOURS - 1L, ChronoUnit.HOURS);

        // 한 시간이라도 누락되면 전체 raw로 fallback해 잘못된 평균을 막습니다.
        if (!hasCompleteHourlySensorCoverage(hourlyRollups, firstRequiredHourEnd, lastCompleteSixHourEnd)) {
            return readSensorTrend(metric, nodeId, from, to, "6h");
        }

        // 시각별 rollup을 빠르게 찾도록 맵으로 변환합니다.
        Map<Instant, HourlySensorRollupItem> hourlyRollupByTime = hourlyRollups.stream()
                .collect(Collectors.toMap(HourlySensorRollupItem::timestamp, item -> item, (left, right) -> right));
        // count 가중 평균으로 만든 6시간 point를 담습니다.
        List<SensorTrendItem> sixHourlyRollups = new java.util.ArrayList<>();

        // 완결된 6시간 구간을 하나씩 순회합니다.
        for (Instant sixHourEnd = firstCompleteSixHourEnd;
             !sixHourEnd.isAfter(lastCompleteSixHourEnd);
             sixHourEnd = sixHourEnd.plus(SIX_HOUR_WINDOW_HOURS, ChronoUnit.HOURS)) {
            // 각 1시간 평균에 곱할 원본 센서 표본 수의 합입니다.
            long totalCount = 0;
            // mean * count를 누적한 원본 값의 합계입니다.
            double weightedTotal = 0;

            // 현재 6시간을 구성하는 여섯 개의 1시간 rollup을 누적합니다.
            for (int offset = SIX_HOUR_WINDOW_HOURS - 1; offset >= 0; offset--) {
                // 현재 6시간 안의 한 시간 종료 시각을 계산합니다.
                Instant hourEnd = sixHourEnd.minus(offset, ChronoUnit.HOURS);
                // coverage 검증을 통과했으므로 해당 시각의 rollup은 존재합니다.
                HourlySensorRollupItem hourlyRollup = hourlyRollupByTime.get(hourEnd);
                // 시간 평균에 원본 건수를 곱해 가중합에 더합니다.
                weightedTotal += hourlyRollup.mean() * hourlyRollup.count();
                // 원본 센서 표본 수도 함께 더합니다.
                totalCount += hourlyRollup.count();
            }

            // 0건 rollup은 유효한 평균을 만들 수 없으므로 오류로 처리합니다.
            if (totalCount == 0) {
                throw new IllegalStateException("센서 6시간 rollup의 count 합계가 0입니다.");
            }

            // 원본 표본 수를 반영한 6시간 평균 point를 추가합니다.
            sixHourlyRollups.add(new SensorTrendItem(sixHourEnd, weightedTotal / totalCount));
        }

        // 첫 완결 6시간의 실제 시작 시각을 계산합니다.
        Instant firstRollupStart = firstCompleteSixHourEnd.minus(SIX_HOUR_WINDOW_HOURS, ChronoUnit.HOURS);

        // 첫 완결 구간 이전의 불완전 raw 부분을 보완합니다.
        List<SensorTrendItem> rawBeforeRollup = from.isBefore(firstRollupStart)
                ? readSensorTrend(metric, nodeId, from, firstRollupStart, "6h")
                : List.of();
        // 마지막 완결 구간 이후의 진행 중 raw 부분을 보완합니다.
        List<SensorTrendItem> rawAfterRollup = lastCompleteSixHourEnd.isBefore(to)
                ? readSensorTrend(metric, nodeId, lastCompleteSixHourEnd, to, "6h")
                : List.of();

        // 완료된 6시간 rollup과 양 끝 raw point를 시각 기준으로 합칩니다.
        return mergeSensorTrendPoints(rawBeforeRollup, rawAfterRollup, sixHourlyRollups);
    }

    // raw 보완 point와 rollup point를 시각순으로 병합합니다.
    private List<SensorTrendItem> mergeSensorTrendPoints(
            List<SensorTrendItem> rawBeforeRollup,
            List<SensorTrendItem> rawAfterRollup,
            List<SensorTrendItem> rollupTrend
    ) {
        // 같은 시각의 point를 하나로 합칠 정렬 맵입니다.
        Map<Instant, SensorTrendItem> mergedTrendByTime = new TreeMap<>();
        // 시작 경계의 raw point를 먼저 넣습니다.
        rawBeforeRollup.forEach(item -> mergedTrendByTime.put(item.getTimestamp(), item));
        // 종료 경계의 raw point를 이어서 넣습니다.
        rawAfterRollup.forEach(item -> mergedTrendByTime.put(item.getTimestamp(), item));
        // 완료된 공식 rollup 값은 같은 시각에서 raw보다 우선합니다.
        rollupTrend.forEach(item -> mergedTrendByTime.put(item.getTimestamp(), item));

        // 중복 없이 시각순으로 정렬된 point를 반환합니다.
        return List.copyOf(mergedTrendByTime.values());
    }

    // rollup 목록에서 요청 범위에 완전히 포함되는 시간대만 골라냅니다.
    private List<HourlySensorRollupItem> selectHourlySensorRollups(
            List<HourlySensorRollupItem> hourlyRollups,
            Instant firstHourEnd,
            Instant lastHourEnd
    ) {
        // 필요한 종료 시각 범위의 point만 정렬해 반환합니다.
        return hourlyRollups.stream()
                .filter(item -> !item.timestamp().isBefore(firstHourEnd))
                .filter(item -> !item.timestamp().isAfter(lastHourEnd))
                .sorted(Comparator.comparing(HourlySensorRollupItem::timestamp))
                .toList();
    }

    // 필요한 모든 시간 rollup이 존재하는지 확인합니다.
    private boolean hasCompleteHourlySensorCoverage(
            List<HourlySensorRollupItem> hourlyRollups,
            Instant firstHourEnd,
            Instant lastHourEnd
    ) {
        // 조회된 rollup 종료 시각을 중복 없이 모읍니다.
        Set<Instant> rollupTimes = hourlyRollups.stream()
                .map(HourlySensorRollupItem::timestamp)
                .collect(Collectors.toSet());

        // 요청이 필요로 하는 모든 1시간 종료 시각을 확인합니다.
        for (Instant hourEnd = firstHourEnd;
             !hourEnd.isAfter(lastHourEnd);
             hourEnd = hourEnd.plus(1, ChronoUnit.HOURS)) {
            // 하나라도 빠지면 부분 rollup을 사용하지 않습니다.
            if (!rollupTimes.contains(hourEnd)) {
                return false;
            }
        }

        // 필요한 모든 rollup이 존재합니다.
        return true;
    }

    // rollup bucket에서 선택한 지표의 시간 평균과 원본 건수를 함께 읽습니다.
    private List<HourlySensorRollupItem> readHourlySensorRollups(
            SensorTrendMetric metric,
            String nodeId,
            Instant from,
            Instant to
    ) {
        // rollup 조회에도 초기화된 InfluxDB query API가 필요합니다.
        requireQueryApi();

        // 종료 시각이 정각이어도 그 시각의 rollup을 포함하도록 1나노초를 더합니다.
        Instant inclusiveTo = to.plusNanos(1);
        // 평균과 count field를 한 시간 point로 pivot하는 Flux를 조립합니다.
        String query = """
                from(bucket: "%s")
                  |> range(start: time(v: "%s"), stop: time(v: "%s"))
                  |> filter(fn: (r) => r._measurement == "%s")
                  |> filter(fn: (r) => r.%s == "%s")
                  |> filter(fn: (r) => r._field == "%s" or r._field == "%s")
                  |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
                  |> keep(columns: ["_time", "%s", "%s"])
                  |> sort(columns: ["_time"])
                """.formatted(
                influxProperties.getRollupBucket(),
                from,
                inclusiveTo,
                influxProperties.getRollupMeasurement(),
                influxProperties.getNodeIdTag(),
                escapeFluxString(nodeId),
                metric.getMeanField(),
                metric.getCountField(),
                metric.getMeanField(),
                metric.getCountField()
        );

        // 평균과 count가 모두 있는 완결 rollup만 내부 DTO로 변환합니다.
        return queryApi.query(query, influxProperties.getOrg()).stream()
                .flatMap(table -> table.getRecords().stream())
                .map(record -> toHourlySensorRollupItemOrNull(record, metric))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    // pivot된 시간 rollup record를 평균·건수 내부 DTO로 변환합니다.
    private HourlySensorRollupItem toHourlySensorRollupItemOrNull(
            FluxRecord record,
            SensorTrendMetric metric
    ) {
        // 지표별 평균 field 값을 읽습니다.
        Object meanValue = record.getValueByKey(metric.getMeanField());
        // 지표별 원본 표본 수 field 값을 읽습니다.
        Object countValue = record.getValueByKey(metric.getCountField());

        // 평균·건수·시각 중 하나라도 없으면 아직 완성되지 않은 rollup으로 제외합니다.
        if (!(meanValue instanceof Number meanNumber)
                || !(countValue instanceof Number countNumber)
                || record.getTime() == null
                || countNumber.longValue() <= 0) {
            return null;
        }

        // 시각·평균·원본 건수를 함께 보존해 이후 6시간 가중 평균에 사용합니다.
        return new HourlySensorRollupItem(
                record.getTime(),
                meanNumber.doubleValue(),
                countNumber.longValue()
        );
    }

    // 시간 rollup 내부 DTO를 공통 그래프 point로 변환합니다.
    private SensorTrendItem toSensorTrendItem(HourlySensorRollupItem rollupItem) {
        // rollup 종료 시각과 평균값을 그대로 반환합니다.
        return new SensorTrendItem(rollupItem.timestamp(), rollupItem.mean());
    }

    // 지정 기간의 선택 지표 일 평균 rollup point를 직접 조회합니다.
    private List<SensorTrendItem> readDailySensorRollup(
            SensorTrendMetric metric,
            String nodeId,
            Instant from,
            Instant to
    ) {
        // 일 rollup 조회에도 초기화된 query API가 필요합니다.
        requireQueryApi();

        // 지표별 mean field만 시각순으로 읽는 Flux를 조립합니다.
        String query = """
                from(bucket: "%s")
                  |> range(start: time(v: "%s"), stop: time(v: "%s"))
                  |> filter(fn: (r) => r._measurement == "%s")
                  |> filter(fn: (r) => r.%s == "%s")
                  |> filter(fn: (r) => r._field == "%s")
                  |> sort(columns: ["_time"])
                """.formatted(
                influxProperties.getRollupBucket(),
                from,
                to,
                DAILY_SENSOR_ROLLUP_MEASUREMENT,
                influxProperties.getNodeIdTag(),
                escapeFluxString(nodeId),
                metric.getMeanField()
        );

        // Flux record를 선택 지표의 시각·평균 point로 변환합니다.
        return queryApi.query(query, influxProperties.getOrg()).stream()
                .flatMap(table -> table.getRecords().stream())
                .map(record -> toSensorTrendItem(record, metric))
                .toList();
    }

    // 인접한 일 rollup 시각이 하루보다 멀면 해당 날짜의 집계가 누락된 것으로 판단합니다.
    private boolean hasDailySensorRollupGap(List<SensorTrendItem> rollupTrend) {
        // 첫 point는 이전 point가 없어 공백 여부를 비교할 수 없습니다.
        for (int index = 1; index < rollupTrend.size(); index++) {
            // 직전 rollup의 다음 날짜 시각을 계산합니다.
            Instant expectedTimestamp = rollupTrend.get(index - 1).getTimestamp().plus(1, ChronoUnit.DAYS);
            // 실제 point가 예상 시각보다 뒤면 중간 날짜 하나 이상이 빠진 것입니다.
            if (rollupTrend.get(index).getTimestamp().isAfter(expectedTimestamp)) {
                return true;
            }
        }

        // 연속된 rollup point만 있으면 내부 날짜 공백이 없습니다.
        return false;
    }

    // raw 또는 daily rollup Flux record를 공통 그래프 point로 변환합니다.
    private SensorTrendItem toSensorTrendItem(FluxRecord record, SensorTrendMetric metric) {
        // 평균값은 Influx 숫자 field여야 Java Double로 변환할 수 있습니다.
        if (!(record.getValue() instanceof Number number) || record.getTime() == null) {
            throw new IllegalStateException(metric.getRawField() + " 추이 조회 결과에 필요한 값이 없습니다.");
        }

        // 집계 시각과 실수 평균값을 공통 그래프 point로 반환합니다.
        return new SensorTrendItem(record.getTime(), number.doubleValue());
    }

    // 지표별 1시간 평균과 해당 평균의 원본 표본 수를 함께 보존합니다.
    private record HourlySensorRollupItem(
            Instant timestamp,
            double mean,
            long count
    ) {
    }

    // 공통 센서 추이에 필요한 지표·식별자·기간·window를 검증합니다.
    private void validateSensorTrendRequest(
            SensorTrendMetric metric,
            String nodeId,
            Instant from,
            Instant to,
            String window
    ) {
        // metric이 없으면 raw·rollup field 이름을 선택할 수 없습니다.
        if (metric == null) {
            throw new IllegalArgumentException("metric이 비어 있습니다.");
        }

        // 기존 CO2 추이와 같은 node·기간·window 규칙을 적용합니다.
        validateCo2TrendRequest(nodeId, from, to, window);
    }

    // InfluxDB 조회 API가 준비되었는지 공통으로 검증합니다.
    private void requireQueryApi() {
        // Spring 초기화가 끝나지 않았거나 연결이 실패했으면 조회를 중단합니다.
        if (queryApi == null) {
            throw new IllegalStateException("InfluxDB queryApi가 초기화되지 않았습니다.");
        }
    }

    // 장기 노드 추이를 일 rollup 우선으로 읽고 비어 있는 구간은 raw로 보완합니다.
    public List<Co2TrendItem> readCo2TrendWithDailyRollup(String nodeId, Instant from, Instant to) {
        // 일 단위 window 규칙으로 node·기간을 검증합니다.
        validateCo2TrendRequest(nodeId, from, to, "1d");

        // 완료된 날짜의 CO2 일 평균 rollup을 담을 목록입니다.
        List<Co2TrendItem> rollupTrend;
        try {
            // rollup bucket에서 장기 조회에 적합한 일 평균 데이터를 읽습니다.
            rollupTrend = readDailyRollup(nodeId, from, to);
        } catch (RuntimeException exception) {
            // rollup 인프라 실패는 화면 장애로 번지지 않게 raw 조회로 대체합니다.
            log.warn(
                    "CO2 1일 rollup 조회에 실패해 raw 데이터를 사용합니다. bucket={}, reason={}",
                    influxProperties.getRollupBucket(),
                    exception.getMessage()
            );
            // raw CO2를 일 단위 평균으로 집계해 fallback 응답을 반환합니다.
            return readCo2Trend(nodeId, from, to, "1d");
        }
        // rollup이 아직 생성되지 않은 기간도 raw 조회로 대체합니다.
        if (rollupTrend.isEmpty()) {
            return readCo2Trend(nodeId, from, to, "1d");
        }

        // rollup이 시작된 첫 시각을 확인합니다.
        Instant firstRollupTime = rollupTrend.get(0).getTimestamp();
        // rollup이 끝난 마지막 시각을 확인합니다.
        Instant lastRollupTime = rollupTrend.get(rollupTrend.size() - 1).getTimestamp();
        // rollup 이전 공백 구간만 raw 일 평균으로 보완합니다.
        List<Co2TrendItem> rawBeforeRollup = from.isBefore(firstRollupTime)
                ? readCo2Trend(nodeId, from, firstRollupTime, "1d")
                : List.of();
        // 오늘처럼 아직 rollup이 완성되지 않은 뒤쪽 구간만 raw 일 평균으로 보완합니다.
        List<Co2TrendItem> rawAfterRollup = lastRollupTime.isBefore(to)
                ? readCo2Trend(nodeId, lastRollupTime, to, "1d")
                : List.of();

        // 완료된 날짜는 rollup, 과거 공백과 오늘은 raw로 보완합니다.
        Map<Instant, Co2TrendItem> mergedTrendByTime = new TreeMap<>();
        // 이전 공백 raw point를 시각 키로 병합합니다.
        rawBeforeRollup.forEach(item -> mergedTrendByTime.put(item.getTimestamp(), item));
        // 이후 공백 raw point를 시각 키로 병합합니다.
        rawAfterRollup.forEach(item -> mergedTrendByTime.put(item.getTimestamp(), item));
        // rollup point를 마지막에 넣어 완료된 구간의 공식 집계값을 우선합니다.
        rollupTrend.forEach(item -> mergedTrendByTime.put(item.getTimestamp(), item));

        // 시각순으로 정렬된 중복 없는 장기 추이 목록을 반환합니다.
        return List.copyOf(mergedTrendByTime.values());
    }

    // 5일·1개월 노드 추이를 시간 rollup 우선으로 읽고 경계 공백만 raw로 보완합니다.
    public List<Co2TrendItem> readCo2TrendWithHourlyRollup(
            String nodeId,
            Instant from,
            Instant to,
            String window
    ) {
        // 노드·기간·응답 window의 기본 형식을 먼저 검증합니다.
        validateCo2TrendRequest(nodeId, from, to, window);

        // 시간 rollup은 현재 1시간 또는 6시간 그래프에만 사용합니다.
        if (!"1h".equals(window) && !"6h".equals(window)) {
            throw new IllegalArgumentException("시간 rollup은 1h 또는 6h window에만 사용할 수 있습니다.");
        }

        // rollup 조회 실패 시 기존 raw 조회로 안전하게 대체합니다.
        try {
            // 5일 화면은 시간 rollup point를 그대로 반환합니다.
            if ("1h".equals(window)) {
                return readHourlyTrendWithRawFallback(nodeId, from, to);
            }

            // 1개월 화면은 시간 rollup을 count 가중 6시간 평균으로 다시 집계합니다.
            return readSixHourlyTrendWithRawFallback(nodeId, from, to);
        } catch (RuntimeException exception) {
            // rollup bucket·Task 오류가 화면 장애가 되지 않게 raw 경로를 사용합니다.
            log.warn(
                    "노드 CO2 1시간 rollup 조회에 실패해 raw 데이터를 사용합니다. nodeId={}, reason={}",
                    nodeId,
                    exception.getMessage()
            );
            // 기존 raw 집계와 같은 window로 fallback 응답을 반환합니다.
            return readCo2Trend(nodeId, from, to, window);
        }
    }

    // 완료된 1시간 rollup을 사용해 5일 화면용 시간별 추이를 구성합니다.
    private List<Co2TrendItem> readHourlyTrendWithRawFallback(String nodeId, Instant from, Instant to) {
        // 원본 기간 안에 완전히 포함되는 첫 1시간의 종료 시각을 계산합니다.
        Instant firstCompleteHourEnd = firstCompleteWindowEnd(from, 1);
        // 원본 기간 안에 완전히 포함되는 마지막 1시간의 종료 시각을 계산합니다.
        Instant lastCompleteHourEnd = floorToWindow(to, 1);

        // 완전히 포함되는 1시간이 없으면 raw만 읽는 편이 정확합니다.
        if (firstCompleteHourEnd.isAfter(lastCompleteHourEnd)) {
            return readCo2Trend(nodeId, from, to, "1h");
        }

        // 시간 rollup에서 필요한 완료 시간대의 평균·건수 point를 읽습니다.
        List<HourlyCo2RollupItem> hourlyRollups = readHourlyRollups(nodeId, from, to);
        // 필요한 모든 시간대가 있을 때만 rollup을 공식 값으로 사용합니다.
        if (!hasCompleteHourlyCoverage(hourlyRollups, firstCompleteHourEnd, lastCompleteHourEnd)) {
            return readCo2Trend(nodeId, from, to, "1h");
        }

        // 요청 기간에 완전히 포함되는 시간 rollup만 시간순으로 선택합니다.
        List<HourlyCo2RollupItem> completeRollups = selectHourlyRollups(
                hourlyRollups,
                firstCompleteHourEnd,
                lastCompleteHourEnd
        );
        // 첫 rollup이 대표하는 실제 시작 시각을 계산합니다.
        Instant firstRollupStart = firstCompleteHourEnd.minus(1, ChronoUnit.HOURS);
        // 마지막 rollup의 종료 시각을 최신 raw 보완 시작점으로 사용합니다.
        Instant lastRollupEnd = lastCompleteHourEnd;

        // 첫 완료 시간 이전의 부분 구간만 raw 1시간 평균으로 보완합니다.
        List<Co2TrendItem> rawBeforeRollup = from.isBefore(firstRollupStart)
                ? readCo2Trend(nodeId, from, firstRollupStart, "1h")
                : List.of();
        // 마지막 완료 시간 이후 진행 중인 구간만 raw 1시간 평균으로 보완합니다.
        List<Co2TrendItem> rawAfterRollup = lastRollupEnd.isBefore(to)
                ? readCo2Trend(nodeId, lastRollupEnd, to, "1h")
                : List.of();

        // 완료 시간은 rollup, 양 끝의 불완전 구간은 raw로 합칩니다.
        return mergeTrendPoints(
                rawBeforeRollup,
                rawAfterRollup,
                completeRollups.stream().map(this::toCo2TrendItem).toList()
        );
    }

    // 완료된 1시간 rollup을 count 가중 6시간 평균으로 묶어 1개월 화면을 구성합니다.
    private List<Co2TrendItem> readSixHourlyTrendWithRawFallback(String nodeId, Instant from, Instant to) {
        // 요청 기간에 온전히 포함되는 첫 6시간 window의 종료 시각을 계산합니다.
        Instant firstCompleteSixHourEnd = firstCompleteWindowEnd(from, SIX_HOUR_WINDOW_HOURS);
        // 요청 기간에 온전히 포함되는 마지막 6시간 window의 종료 시각을 계산합니다.
        Instant lastCompleteSixHourEnd = floorToWindow(to, SIX_HOUR_WINDOW_HOURS);

        // 온전한 6시간 window가 없으면 raw 6시간 평균으로 정확히 응답합니다.
        if (firstCompleteSixHourEnd.isAfter(lastCompleteSixHourEnd)) {
            return readCo2Trend(nodeId, from, to, "6h");
        }

        // 필요한 6시간 window를 구성할 시간 rollup을 모두 읽습니다.
        List<HourlyCo2RollupItem> hourlyRollups = readHourlyRollups(nodeId, from, to);
        // 첫 6시간 window가 요구하는 첫 1시간 종료 시각을 계산합니다.
        Instant firstRequiredHourEnd = firstCompleteSixHourEnd.minus(SIX_HOUR_WINDOW_HOURS - 1L, ChronoUnit.HOURS);
        // 마지막 6시간 window가 요구하는 마지막 1시간 종료 시각을 사용합니다.
        Instant lastRequiredHourEnd = lastCompleteSixHourEnd;

        // 한 시간이라도 빠지면 단순 평균·부분 값 대신 raw 전체 조회로 정확성을 지킵니다.
        if (!hasCompleteHourlyCoverage(hourlyRollups, firstRequiredHourEnd, lastRequiredHourEnd)) {
            return readCo2Trend(nodeId, from, to, "6h");
        }

        // 1시간 평균과 건수를 시각으로 빠르게 찾도록 맵으로 변환합니다.
        Map<Instant, HourlyCo2RollupItem> hourlyRollupByTime = hourlyRollups.stream()
                .collect(Collectors.toMap(HourlyCo2RollupItem::timestamp, item -> item, (left, right) -> right));
        // count 가중 평균으로 만든 6시간 CO2 point를 담을 목록입니다.
        List<Co2TrendItem> sixHourlyRollups = new java.util.ArrayList<>();

        // 첫 완료 6시간부터 마지막 완료 6시간까지 하나씩 집계합니다.
        for (Instant sixHourEnd = firstCompleteSixHourEnd;
             !sixHourEnd.isAfter(lastCompleteSixHourEnd);
             sixHourEnd = sixHourEnd.plus(SIX_HOUR_WINDOW_HOURS, ChronoUnit.HOURS)) {
            // 현재 6시간에 포함되는 raw CO2 건수의 합입니다.
            long totalCount = 0;
            // 시간별 평균에 건수를 곱한 가중합입니다.
            double weightedTotal = 0;

            // 6개 1시간 rollup을 모두 더해 raw 전체 평균과 같은 의미를 만듭니다.
            for (int offset = SIX_HOUR_WINDOW_HOURS - 1; offset >= 0; offset--) {
                // 현재 6시간 안의 한 시간 종료 시각을 계산합니다.
                Instant hourEnd = sixHourEnd.minus(offset, ChronoUnit.HOURS);
                // coverage 검증을 통과했으므로 해당 rollup은 반드시 존재합니다.
                HourlyCo2RollupItem hourlyRollup = hourlyRollupByTime.get(hourEnd);
                // 평균과 건수를 곱해 이 시간의 CO2 총합에 해당하는 값을 누적합니다.
                weightedTotal += hourlyRollup.co2Mean() * hourlyRollup.co2Count();
                // 실제 센서 수집 건수를 함께 누적합니다.
                totalCount += hourlyRollup.co2Count();
            }

            // 0건 rollup은 coverage 대상이 아니므로 계산 오류로 처리합니다.
            if (totalCount == 0) {
                throw new IllegalStateException("CO2 6시간 rollup의 count 합계가 0입니다.");
            }

            // 가중 평균을 ppm 정수로 반올림해 그래프 point를 추가합니다.
            sixHourlyRollups.add(new Co2TrendItem(
                    sixHourEnd,
                    (int) Math.round(weightedTotal / totalCount)
            ));
        }

        // 첫 완료 6시간 window가 실제로 시작하는 시각을 구합니다.
        Instant firstRollupStart = firstCompleteSixHourEnd.minus(SIX_HOUR_WINDOW_HOURS, ChronoUnit.HOURS);
        // 마지막 완료 6시간 window가 끝나는 시각을 최신 raw 보완 시작점으로 사용합니다.
        Instant lastRollupEnd = lastCompleteSixHourEnd;

        // 첫 완료 window 이전의 부분 구간만 raw 6시간 평균으로 보완합니다.
        List<Co2TrendItem> rawBeforeRollup = from.isBefore(firstRollupStart)
                ? readCo2Trend(nodeId, from, firstRollupStart, "6h")
                : List.of();
        // 마지막 완료 window 이후 진행 중인 구간만 raw 6시간 평균으로 보완합니다.
        List<Co2TrendItem> rawAfterRollup = lastRollupEnd.isBefore(to)
                ? readCo2Trend(nodeId, lastRollupEnd, to, "6h")
                : List.of();

        // 완료된 6시간은 rollup, 양 끝의 미완료 구간은 raw로 합칩니다.
        return mergeTrendPoints(rawBeforeRollup, rawAfterRollup, sixHourlyRollups);
    }

    // rollup point의 앞뒤 raw 보완값과 완료 rollup 값을 시각 기준으로 병합합니다.
    private List<Co2TrendItem> mergeTrendPoints(
            List<Co2TrendItem> rawBeforeRollup,
            List<Co2TrendItem> rawAfterRollup,
            List<Co2TrendItem> rollupTrend
    ) {
        // 같은 시각의 raw와 rollup을 하나로 합칠 정렬 맵입니다.
        Map<Instant, Co2TrendItem> mergedTrendByTime = new TreeMap<>();
        // 시작 부분 raw point를 먼저 넣습니다.
        rawBeforeRollup.forEach(item -> mergedTrendByTime.put(item.getTimestamp(), item));
        // 종료 부분 raw point를 이어서 넣습니다.
        rawAfterRollup.forEach(item -> mergedTrendByTime.put(item.getTimestamp(), item));
        // 완료 rollup은 검증된 공식 집계값이므로 같은 시각에서 마지막에 덮어씁니다.
        rollupTrend.forEach(item -> mergedTrendByTime.put(item.getTimestamp(), item));

        // 중복 없는 시각순 그래프 point를 반환합니다.
        return List.copyOf(mergedTrendByTime.values());
    }

    // rollup 목록에서 요청 기간에 완전히 포함되는 시간대만 선택합니다.
    private List<HourlyCo2RollupItem> selectHourlyRollups(
            List<HourlyCo2RollupItem> hourlyRollups,
            Instant firstHourEnd,
            Instant lastHourEnd
    ) {
        // 완결 시간 범위 안의 point만 시간순으로 골라 반환합니다.
        return hourlyRollups.stream()
                .filter(item -> !item.timestamp().isBefore(firstHourEnd))
                .filter(item -> !item.timestamp().isAfter(lastHourEnd))
                .sorted(Comparator.comparing(HourlyCo2RollupItem::timestamp))
                .toList();
    }

    // 요청 범위의 연속된 1시간 rollup이 빠짐없이 존재하는지 확인합니다.
    private boolean hasCompleteHourlyCoverage(
            List<HourlyCo2RollupItem> hourlyRollups,
            Instant firstHourEnd,
            Instant lastHourEnd
    ) {
        // 조회된 rollup 시각을 중복 없이 저장합니다.
        Set<Instant> rollupTimes = hourlyRollups.stream()
                .map(HourlyCo2RollupItem::timestamp)
                .collect(Collectors.toSet());

        // 필요한 모든 시간 종료 시각을 순서대로 검사합니다.
        for (Instant hourEnd = firstHourEnd;
             !hourEnd.isAfter(lastHourEnd);
             hourEnd = hourEnd.plus(1, ChronoUnit.HOURS)) {
            // 하나라도 없으면 부분 rollup을 섞지 않고 raw fallback을 선택합니다.
            if (!rollupTimes.contains(hourEnd)) {
                return false;
            }
        }

        // 필요한 모든 1시간 rollup이 존재합니다.
        return true;
    }

    // 기간 안에 완전히 포함되는 첫 window의 종료 시각을 계산합니다.
    private Instant firstCompleteWindowEnd(Instant from, int windowHours) {
        // 요청 시작을 UTC epoch 기준 window 경계로 내림합니다.
        Instant windowStart = floorToWindow(from, windowHours);
        // 경계에서 시작하면 한 window 뒤, 중간에서 시작하면 두 window 뒤가 첫 완결 window입니다.
        return from.equals(windowStart)
                ? windowStart.plus(windowHours, ChronoUnit.HOURS)
                : windowStart.plus(windowHours * 2L, ChronoUnit.HOURS);
    }

    // UTC epoch 기준의 window 시작 경계로 시각을 내림합니다.
    private Instant floorToWindow(Instant instant, int windowHours) {
        // window 크기를 초 단위로 변환합니다.
        long windowSeconds = Duration.ofHours(windowHours).toSeconds();
        // 음수 epoch도 올바르게 처리하도록 floorDiv로 시작 초를 계산합니다.
        long windowStartSeconds = Math.floorDiv(instant.getEpochSecond(), windowSeconds) * windowSeconds;
        // 원래 나노초는 버리고 window 시작 UTC 시각을 반환합니다.
        return Instant.ofEpochSecond(windowStartSeconds);
    }

    // rollup bucket에서 노드의 시간 평균과 해당 평균을 만든 raw 건수를 함께 읽습니다.
    private List<HourlyCo2RollupItem> readHourlyRollups(String nodeId, Instant from, Instant to) {
        // 초기화되지 않은 query API로 rollup을 읽지 않게 차단합니다.
        if (queryApi == null) {
            throw new IllegalStateException("InfluxDB queryApi가 초기화되지 않았습니다.");
        }

        // 종료 시각이 정각이어도 해당 종료 시각 rollup을 포함하도록 1나노초를 더합니다.
        Instant inclusiveTo = to.plusNanos(1);
        // 평균과 count field를 한 시간 point로 pivot하는 Flux를 조립합니다.
        String query = """
                from(bucket: "%s")
                  |> range(start: time(v: "%s"), stop: time(v: "%s"))
                  |> filter(fn: (r) => r._measurement == "%s")
                  |> filter(fn: (r) => r.%s == "%s")
                  |> filter(fn: (r) => r._field == "co2_mean" or r._field == "co2_count")
                  |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
                  |> keep(columns: ["_time", "co2_mean", "co2_count"])
                  |> sort(columns: ["_time"])
                """.formatted(
                influxProperties.getRollupBucket(),
                from,
                inclusiveTo,
                influxProperties.getRollupMeasurement(),
                influxProperties.getNodeIdTag(),
                escapeFluxString(nodeId)
        );

        // 평균과 count가 모두 있는 완결 시간 rollup만 Java 객체로 변환합니다.
        return queryApi.query(query, influxProperties.getOrg()).stream()
                .flatMap(table -> table.getRecords().stream())
                .map(this::toHourlyCo2RollupItemOrNull)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    // pivot된 시간 rollup record를 평균과 raw 건수를 가진 내부 DTO로 변환합니다.
    private HourlyCo2RollupItem toHourlyCo2RollupItemOrNull(FluxRecord record) {
        // pivot 결과에서 시간 평균 field를 읽습니다.
        Object meanValue = record.getValueByKey("co2_mean");
        // pivot 결과에서 해당 평균의 raw 건수 field를 읽습니다.
        Object countValue = record.getValueByKey("co2_count");

        // field가 아직 모두 기록되지 않았거나 숫자가 아니면 다음 조회에서 다시 보도록 제외합니다.
        if (!(meanValue instanceof Number meanNumber)
                || !(countValue instanceof Number countNumber)
                || record.getTime() == null
                || countNumber.longValue() <= 0) {
            return null;
        }

        // 시각·평균·건수를 가진 시간 rollup 내부 DTO를 반환합니다.
        return new HourlyCo2RollupItem(
                record.getTime(),
                meanNumber.doubleValue(),
                countNumber.longValue()
        );
    }

    // 시간 rollup의 실수 평균을 화면 그래프가 쓸 정수 ppm point로 변환합니다.
    private Co2TrendItem toCo2TrendItem(HourlyCo2RollupItem rollupItem) {
        // rollup 종료 시각과 반올림한 평균 ppm을 그래프 point로 반환합니다.
        return new Co2TrendItem(
                rollupItem.timestamp(),
                (int) Math.round(rollupItem.co2Mean())
        );
    }

    // 1시간 CO2 평균과 원본 측정 건수를 함께 보존하는 내부 rollup DTO입니다.
    private record HourlyCo2RollupItem(
            Instant timestamp,
            double co2Mean,
            long co2Count
    ) {
    }

    // 지정 기간의 노드별 CO2 일 평균 rollup point를 직접 조회합니다.
    private List<Co2TrendItem> readDailyRollup(String nodeId, Instant from, Instant to) {
        // 초기화되지 않은 query API로 조회하지 않게 차단합니다.
        if (queryApi == null) {
            throw new IllegalStateException("InfluxDB queryApi가 초기화되지 않았습니다.");
        }

        // rollup bucket의 co2_mean field만 시각순으로 읽는 Flux를 조립합니다.
        String query = """
                from(bucket: "%s")
                  |> range(start: time(v: "%s"), stop: time(v: "%s"))
                  |> filter(fn: (r) => r._measurement == "%s")
                  |> filter(fn: (r) => r.%s == "%s")
                  |> filter(fn: (r) => r._field == "co2_mean")
                  |> sort(columns: ["_time"])
                """.formatted(
                influxProperties.getRollupBucket(),
                from,
                to,
                DAILY_CO2_ROLLUP_MEASUREMENT,
                influxProperties.getNodeIdTag(),
                nodeId.replace("\"", "\\\"")
        );

        // Flux 결과를 화면용 CO2 point 목록으로 변환합니다.
        return queryApi.query(query, influxProperties.getOrg()).stream()
                .flatMap(table -> table.getRecords().stream())
                .map(this::toCo2TrendItem)
                .toList();
    }

    // 노드별 CO2 추이에 필요한 식별자·기간·window의 기본 형식을 검증합니다.
    private void validateCo2TrendRequest(String nodeId, Instant from, Instant to, String window) {
        // node ID가 없으면 노드별 조건을 만들 수 없습니다.
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId가 비어 있습니다.");
        }

        // 시작 시각이 없으면 Flux range 시작점을 정할 수 없습니다.
        if (from == null) {
            throw new IllegalArgumentException("from이 비어 있습니다.");
        }

        // 종료 시각이 없으면 Flux range 끝점을 정할 수 없습니다.
        if (to == null) {
            throw new IllegalArgumentException("to가 비어 있습니다.");
        }

        // 역전된 기간은 의미 있는 시계열 조회 범위가 아닙니다.
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from은 to보다 이후일 수 없습니다.");
        }

        // Flux aggregateWindow가 지원하는 숫자+초·분·시·일 형식만 허용합니다.
        if (window == null || !window.matches("\\d+[smhd]")) {
            throw new IllegalArgumentException("window 형식이 올바르지 않습니다.");
        }
    }

    // 여러 활성 노드의 동등 가중 CO2 평균 추이 point를 raw bucket에서 읽습니다.
    public List<Co2TrendItem> readAverageCo2Trend(List<String> nodeIds, Instant from, Instant to, String window) {
        // 노드 목록이 없으면 캠퍼스 평균을 계산할 대상이 없습니다.
        if (nodeIds == null || nodeIds.isEmpty()) {
            throw new IllegalArgumentException("nodeIds가 비어 있습니다.");
        }

        // 빈 node ID가 있으면 Flux contains 조건이 불완전해집니다.
        if (nodeIds.stream().anyMatch(nodeId -> nodeId == null || nodeId.isBlank())) {
            throw new IllegalArgumentException("nodeIds에 비어 있는 nodeId가 포함되어 있습니다.");
        }

        // 시작 시각이 없으면 평균 추이 범위를 계산할 수 없습니다.
        if (from == null) {
            throw new IllegalArgumentException("from이 비어 있습니다.");
        }

        // 종료 시각이 없으면 평균 추이 범위를 계산할 수 없습니다.
        if (to == null) {
            throw new IllegalArgumentException("to가 비어 있습니다.");
        }

        // 시작이 종료보다 늦은 역전된 기간은 허용하지 않습니다.
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from은 to보다 이후일 수 없습니다.");
        }

        // raw CO2를 묶을 Flux window는 숫자+시간 단위 형식이어야 합니다.
        if (window == null || !window.matches("\\d+[smhd]")) {
            throw new IllegalArgumentException("window 형식이 올바르지 않습니다.");
        }

        // 초기화되지 않은 query API로 평균 쿼리를 실행하지 않게 차단합니다.
        if (queryApi == null) {
            throw new IllegalStateException("InfluxDB queryApi가 초기화되지 않았습니다.");
        }

        // 중복을 제거하고 Flux contains에 넣을 node ID 문자열 집합을 만듭니다.
        String nodeIdSet = toNodeIdSet(nodeIds);

        // 노드별 window 평균 뒤 같은 시각의 노드 평균을 계산하는 Flux를 조립합니다.
        String query = """
                from(bucket: "%s")
                  |> range(start: time(v: "%s"), stop: time(v: "%s"))
                  |> filter(fn: (r) => r._measurement == "%s")
                  |> filter(fn: (r) => contains(value: r.%s, set: [%s]))
                  |> filter(fn: (r) => r._field == "co2_ppm")
                  |> aggregateWindow(every: %s, fn: mean, createEmpty: false)
                  |> group(columns: ["_time"])
                  |> mean(column: "_value")
                  |> group()
                  |> sort(columns: ["_time"])
                """.formatted(
                influxProperties.getBucket(),
                from,
                to,
                influxProperties.getMeasurement(),
                influxProperties.getNodeIdTag(),
                nodeIdSet,
                window
        );

        // 조립한 평균 추이 Flux를 organization 범위에서 실행합니다.
        List<FluxTable> tables = queryApi.query(query, influxProperties.getOrg());

        // 각 시각의 캠퍼스 평균 CO2 record를 화면용 point 목록으로 변환합니다.
        return tables.stream()
                .flatMap(table -> table.getRecords().stream())
                .map(record -> toCo2TrendItem(record))
                .toList();
    }

    // 분석 화면의 긴 기간 평균 추이를 1시간 rollup 우선으로 읽고 raw로 보완합니다.
    public List<Co2TrendItem> readAverageCo2TrendWithHourlyRollup(
            List<String> nodeIds,
            Instant from,
            Instant to
    ) {
        // 여러 노드 평균 추이에 필요한 노드 목록과 기간을 검증합니다.
        validateAverageTrendRequest(nodeIds, from, to);

        // 완료된 시간대의 1시간 rollup 평균을 담을 목록입니다.
        List<Co2TrendItem> rollupTrend;
        try {
            // rollup bucket에서 시간별 노드 평균 CO2 추이를 읽습니다.
            rollupTrend = readAverageHourlyRollup(nodeIds, from, to);
        } catch (RuntimeException exception) {
            // rollup 조회 실패는 raw 1시간 평균 조회로 대체해 화면을 계속 제공합니다.
            log.warn(
                    "CO2 1시간 rollup 조회에 실패해 raw 데이터를 사용합니다. bucket={}, reason={}",
                    influxProperties.getRollupBucket(),
                    exception.getMessage()
            );
            // raw 데이터에서 1시간 window 평균을 만들어 fallback 응답을 반환합니다.
            return readAverageCo2Trend(nodeIds, from, to, "1h");
        }
        // 아직 rollup이 생성되지 않은 기간은 raw 1시간 평균으로 대체합니다.
        if (rollupTrend.isEmpty()) {
            return readAverageCo2Trend(nodeIds, from, to, "1h");
        }

        // rollup 결과가 시작되는 첫 시각을 구합니다.
        Instant firstRollupTime = rollupTrend.get(0).getTimestamp();
        // rollup 결과가 끝나는 마지막 시각을 구합니다.
        Instant lastRollupTime = rollupTrend.get(rollupTrend.size() - 1).getTimestamp();
        // rollup 이전 미집계 구간만 raw 1시간 평균으로 보완합니다.
        List<Co2TrendItem> rawBeforeRollup = from.isBefore(firstRollupTime)
                ? readAverageCo2Trend(nodeIds, from, firstRollupTime, "1h")
                : List.of();
        // 마지막 완료 rollup 이후 구간만 raw 1시간 평균으로 보완합니다.
        List<Co2TrendItem> rawAfterRollup = lastRollupTime.isBefore(to)
                ? readAverageCo2Trend(nodeIds, lastRollupTime, to, "1h")
                : List.of();

        // 집계가 없는 앞뒤 구간만 원본으로 보완해 최신 값을 유지합니다.
        Map<Instant, Co2TrendItem> mergedTrendByTime = new TreeMap<>();
        // rollup 이전 raw point를 시각 키로 병합합니다.
        rawBeforeRollup.forEach(item -> mergedTrendByTime.put(item.getTimestamp(), item));
        // rollup 이후 raw point를 시각 키로 병합합니다.
        rawAfterRollup.forEach(item -> mergedTrendByTime.put(item.getTimestamp(), item));
        // 완료된 시간대의 rollup point를 우선하는 값으로 병합합니다.
        rollupTrend.forEach(item -> mergedTrendByTime.put(item.getTimestamp(), item));

        // 시각순으로 정렬된 중복 없는 평균 CO2 추이를 반환합니다.
        return List.copyOf(mergedTrendByTime.values());
    }

    // rollup bucket에서 여러 노드의 1시간 CO2 평균을 동등 가중으로 계산합니다.
    private List<Co2TrendItem> readAverageHourlyRollup(List<String> nodeIds, Instant from, Instant to) {
        // 초기화되지 않은 query API로 rollup을 조회하지 않게 차단합니다.
        if (queryApi == null) {
            throw new IllegalStateException("InfluxDB queryApi가 초기화되지 않았습니다.");
        }

        // 노드별 co2_mean을 같은 시각별로 평균 내는 rollup Flux를 조립합니다.
        String query = """
                from(bucket: "%s")
                  |> range(start: time(v: "%s"), stop: time(v: "%s"))
                  |> filter(fn: (r) => r._measurement == "%s")
                  |> filter(fn: (r) => contains(value: r.%s, set: [%s]))
                  |> filter(fn: (r) => r._field == "co2_mean")
                  |> group(columns: ["_time"])
                  |> mean(column: "_value")
                  |> group()
                  |> sort(columns: ["_time"])
                """.formatted(
                influxProperties.getRollupBucket(),
                from,
                to,
                influxProperties.getRollupMeasurement(),
                influxProperties.getNodeIdTag(),
                toNodeIdSet(nodeIds)
        );

        // rollup Flux 결과를 화면용 시각·ppm point 목록으로 변환합니다.
        return queryApi.query(query, influxProperties.getOrg()).stream()
                .flatMap(table -> table.getRecords().stream())
                .map(this::toCo2TrendItem)
                .toList();
    }

    // 여러 노드 평균 rollup 조회의 노드 목록과 기간을 검증합니다.
    private void validateAverageTrendRequest(List<String> nodeIds, Instant from, Instant to) {
        // 평균을 계산할 노드가 하나도 없으면 요청이 성립하지 않습니다.
        if (nodeIds == null || nodeIds.isEmpty()) {
            throw new IllegalArgumentException("nodeIds가 비어 있습니다.");
        }

        // 빈 node ID가 있으면 rollup Flux 조건을 신뢰할 수 없습니다.
        if (nodeIds.stream().anyMatch(nodeId -> nodeId == null || nodeId.isBlank())) {
            throw new IllegalArgumentException("nodeIds에 비어 있는 nodeId가 포함되어 있습니다.");
        }

        // 시작 시각이 없으면 rollup 조회 범위를 정할 수 없습니다.
        if (from == null) {
            throw new IllegalArgumentException("from이 비어 있습니다.");
        }

        // 종료 시각이 없으면 rollup 조회 범위를 정할 수 없습니다.
        if (to == null) {
            throw new IllegalArgumentException("to가 비어 있습니다.");
        }

        // 역전된 기간은 유효한 시계열 조회 범위가 아닙니다.
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from은 to보다 이후일 수 없습니다.");
        }
    }

    // node ID 목록을 Flux contains 함수가 받을 문자열 배열 원소로 변환합니다.
    private String toNodeIdSet(List<String> nodeIds) {
        // 중복 제거·Flux 이스케이프·따옴표·쉼표 결합을 순서대로 수행합니다.
        return nodeIds.stream()
                .distinct()
                .map(nodeId -> "\"" + escapeFluxString(nodeId) + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    // 공간 상태 평가에 필요한 최근 30분 센서 trend feature를 읽어 계산합니다.
    public AiSensorTrendData readAiSensorTrend(String nodeId, Instant to) {
        // node ID가 없으면 AI 평가용 센서 시계열을 찾을 수 없습니다.
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId가 비어 있습니다.");
        }

        // 평가 기준 시각이 없으면 최근 30분 범위를 정할 수 없습니다.
        if (to == null) {
            throw new IllegalArgumentException("to가 비어 있습니다.");
        }

        // 기준 시각 이전 30분을 AI feature 계산 구간으로 사용합니다.
        Instant from = to.minus(30, ChronoUnit.MINUTES);
        // 온도·습도·CO2 원본 point를 한 번에 조회합니다.
        List<Dht22MeasurementItem> measurements = queryMeasurements(nodeId, from, to);

        // 최신값·CO2 변화·1000ppm 초과 시간·온도 변화·마지막 움직임을 하나의 입력 DTO로 반환합니다.
        return new AiSensorTrendData(
                latestMeasurement(measurements),
                calculateCo2Rate10m(measurements, to),
                calculateCo2Over1000Minutes(measurements, from, to),
                calculateTempRate30m(measurements),
                readLatestIntegerField(nodeId, "minutes_since_motion", from, to)
        );
    }

    // 지정한 노드와 기간의 온도·습도·CO2 raw field를 한 행의 측정 DTO로 읽습니다.
    private List<Dht22MeasurementItem> queryMeasurements(String nodeId, Instant from, Instant to) {
        // 초기화되지 않은 query API로 raw 센서값을 읽지 않게 차단합니다.
        if (queryApi == null) {
            throw new IllegalStateException("InfluxDB queryApi가 초기화되지 않았습니다.");
        }

        // 세 field를 시간별 한 record로 pivot해 시각순으로 읽는 Flux를 조립합니다.
        String query = """
                from(bucket: "%s")
                  |> range(start: time(v: "%s"), stop: time(v: "%s"))
                  |> filter(fn: (r) => r._measurement == "%s")
                  |> filter(fn: (r) => r.%s == "%s")
                  |> filter(fn: (r) => r._field == "temperature_c" or r._field == "humidity_pct" or r._field == "co2_ppm")
                  |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
                  |> sort(columns: ["_time"])
                """.formatted(
                influxProperties.getBucket(),
                from,
                to,
                influxProperties.getMeasurement(),
                influxProperties.getNodeIdTag(),
                nodeId.replace("\"", "\\\"")
        );

        // 조립한 raw 센서 Flux를 organization 범위에서 실행합니다.
        List<FluxTable> tables = queryApi.query(query, influxProperties.getOrg());

        // Flux record를 온도·습도·CO2·시각을 가진 측정 DTO 목록으로 변환합니다.
        return tables.stream()
                .flatMap(table -> table.getRecords().stream())
                .map(record -> toMeasurementItem(record))
                .toList();
    }

    // 측정 목록에서 가장 최근 시각의 센서값을 선택합니다.
    private Dht22MeasurementItem latestMeasurement(List<Dht22MeasurementItem> measurements) {
        // timestamp가 가장 큰 DTO를 반환하고 없으면 null을 반환합니다.
        return measurements.stream()
                .max(Comparator.comparing(Dht22MeasurementItem::getTimestamp))
                .orElse(null);
    }

    // 최신 CO2와 10분 전 기준 CO2의 차이로 10분 변화량을 계산합니다.
    private Double calculateCo2Rate10m(List<Dht22MeasurementItem> measurements, Instant to) {
        // CO2가 있는 가장 최근 측정값을 찾습니다.
        Dht22MeasurementItem latest = latestCo2Measurement(measurements);
        // CO2 측정이 전혀 없으면 변화량을 계산할 수 없습니다.
        if (latest == null) {
            return null;
        }

        // 평가 기준 시각에서 정확히 10분 전을 기준점으로 잡습니다.
        Instant target = to.minus(10, ChronoUnit.MINUTES);
        // 기준점 이전 또는 같은 시각 중 가장 가까운 CO2 측정값을 고릅니다.
        Dht22MeasurementItem baseline = measurements.stream()
                .filter(item -> item.getCo2Ppm() != null)
                .filter(item -> !item.getTimestamp().isAfter(target))
                .max(Comparator.comparing(Dht22MeasurementItem::getTimestamp))
                .orElse(null);

        // 10분 전 기준점 이전 CO2가 없으면 변화량을 신뢰할 수 없습니다.
        if (baseline == null) {
            return null;
        }
        // 최신 ppm에서 기준 ppm을 빼 최근 10분 CO2 증감량을 반환합니다.
        return latest.getCo2Ppm().doubleValue() - baseline.getCo2Ppm().doubleValue();
    }

    // 측정 목록에서 CO2 값이 존재하는 가장 최근 record를 선택합니다.
    private Dht22MeasurementItem latestCo2Measurement(List<Dht22MeasurementItem> measurements) {
        // null CO2를 제외하고 timestamp가 가장 큰 DTO를 반환합니다.
        return measurements.stream()
                .filter(item -> item.getCo2Ppm() != null)
                .max(Comparator.comparing(Dht22MeasurementItem::getTimestamp))
                .orElse(null);
    }

    // 최근 구간에서 CO2가 1000ppm을 초과한 누적 시간을 분 단위로 계산합니다.
    private Integer calculateCo2Over1000Minutes(List<Dht22MeasurementItem> measurements, Instant from, Instant to) {
        // CO2가 있는 point만 시각순으로 정리합니다.
        List<Dht22MeasurementItem> co2Measurements = measurements.stream()
                .filter(item -> item.getCo2Ppm() != null)
                .sorted(Comparator.comparing(Dht22MeasurementItem::getTimestamp))
                .toList();

        // CO2 point가 없으면 초과 시간을 계산할 근거가 없습니다.
        if (co2Measurements.isEmpty()) {
            return null;
        }

        // 1000ppm 초과 구간의 누적 초를 저장합니다.
        long seconds = 0;
        // 각 측정값이 다음 측정값 전까지 유지된다고 보고 구간별 시간을 계산합니다.
        for (int index = 0; index < co2Measurements.size(); index++) {
            // 현재 구간의 CO2 기준 point를 가져옵니다.
            Dht22MeasurementItem current = co2Measurements.get(index);
            // 첫 point가 조회 시작보다 이르면 실제 조회 시작을 구간 시작으로 사용합니다.
            Instant segmentStart = current.getTimestamp().isBefore(from) ? from : current.getTimestamp();
            // 다음 point가 있으면 그 시각까지, 없으면 조회 종료까지를 구간 끝으로 사용합니다.
            Instant segmentEnd = index + 1 < co2Measurements.size()
                    ? co2Measurements.get(index + 1).getTimestamp()
                    : to;

            // 현재 ppm이 기준을 넘고 양수 길이 구간일 때만 초과 시간을 누적합니다.
            if (current.getCo2Ppm() > 1_000 && segmentEnd.isAfter(segmentStart)) {
                seconds += Duration.between(segmentStart, segmentEnd).toSeconds();
            }
        }
        // 누적 초를 가장 가까운 분 단위 정수로 반올림해 반환합니다.
        return (int) Math.round(seconds / 60.0);
    }

    // 최근 30분 첫 온도와 마지막 온도의 차이로 온도 변화량을 계산합니다.
    private Double calculateTempRate30m(List<Dht22MeasurementItem> measurements) {
        // 온도가 있는 point만 시각순으로 정리합니다.
        List<Dht22MeasurementItem> temperatureMeasurements = measurements.stream()
                .filter(item -> item.getTemperature() != null)
                .sorted(Comparator.comparing(Dht22MeasurementItem::getTimestamp))
                .toList();

        // 비교할 두 point가 없으면 온도 변화량을 계산하지 않습니다.
        if (temperatureMeasurements.size() < 2) {
            return null;
        }

        // 구간 시작의 온도 point를 선택합니다.
        Dht22MeasurementItem first = temperatureMeasurements.get(0);
        // 구간 종료의 가장 최근 온도 point를 선택합니다.
        Dht22MeasurementItem latest = temperatureMeasurements.get(temperatureMeasurements.size() - 1);
        // 마지막 온도에서 첫 온도를 빼 구간 온도 변화량을 반환합니다.
        return latest.getTemperature() - first.getTemperature();
    }

    // 지정 기간에서 정수형 field의 마지막 값을 읽습니다.
    private Integer readLatestIntegerField(String nodeId, String fieldName, Instant from, Instant to) {
        // 초기화되지 않은 query API로 최신 field를 조회하지 않게 차단합니다.
        if (queryApi == null) {
            throw new IllegalStateException("InfluxDB queryApi가 초기화되지 않았습니다.");
        }

        // 노드·field 조건으로 최신 한 record만 반환하는 Flux를 조립합니다.
        String query = """
                from(bucket: "%s")
                  |> range(start: time(v: "%s"), stop: time(v: "%s"))
                  |> filter(fn: (r) => r._measurement == "%s")
                  |> filter(fn: (r) => r.%s == "%s")
                  |> filter(fn: (r) => r._field == "%s")
                  |> last()
                """.formatted(
                influxProperties.getBucket(),
                from,
                to,
                influxProperties.getMeasurement(),
                influxProperties.getNodeIdTag(),
                escapeFluxString(nodeId),
                fieldName
        );

        // 첫 record가 있으면 정수로 변환하고 없으면 null을 반환합니다.
        return queryApi.query(query, influxProperties.getOrg())
                .stream()
                .flatMap(table -> table.getRecords().stream())
                .findFirst()
                .map(record -> toIntegerOrNull(record.getValue(), fieldName))
                .orElse(null);
    }

    // pivot된 Flux record를 온도·습도·CO2·시각 DTO로 변환합니다.
    private Dht22MeasurementItem toMeasurementItem(FluxRecord record) {
        // pivot 결과의 temperature_c 값을 읽습니다.
        Object temperatureValue = record.getValueByKey("temperature_c");
        // pivot 결과의 humidity_pct 값을 읽습니다.
        Object humidityValue = record.getValueByKey("humidity_pct");
        // pivot 결과의 co2_ppm 값을 읽습니다.
        Object co2Value = record.getValueByKey("co2_ppm");

        // 온도는 숫자 field여야 DTO의 Double로 변환할 수 있습니다.
        if (!(temperatureValue instanceof Number temperatureNumber)) {
            throw new IllegalStateException("temperature_c 필드를 Double로 변환할 수 없습니다.");
        }

        // 습도는 숫자 field여야 DTO의 Double로 변환할 수 있습니다.
        if (!(humidityValue instanceof Number humidityNumber)) {
            throw new IllegalStateException("humidity_pct 필드를 Double로 변환할 수 없습니다.");
        }

        // Influx 숫자 타입을 Java Double 온도로 변환합니다.
        Double temperature = temperatureNumber.doubleValue();
        // Influx 숫자 타입을 Java Double 습도로 변환합니다.
        Double humidity = humidityNumber.doubleValue();
        // CO2 숫자는 ppm 정수로 반올림하고 없으면 null을 유지합니다.
        Integer co2Ppm = toIntegerOrNull(co2Value);

        // 시계열 DTO에는 측정 시각이 반드시 필요합니다.
        if (record.getTime() == null) {
            throw new IllegalStateException("InfluxDB DHT22 조회 결과에 필요한 값이 없습니다.");
        }

        // 변환된 환경값과 시각으로 측정 DTO를 반환합니다.
        return new Dht22MeasurementItem(
                temperature,
                humidity,
                co2Ppm,
                record.getTime()
        );
    }

    // CO2 Flux record를 화면 그래프가 사용할 시각·ppm point로 변환합니다.
    private Co2TrendItem toCo2TrendItem(FluxRecord record) {
        // 집계 평균 또는 raw 값을 ppm 정수로 반올림합니다.
        Integer co2Ppm = toIntegerOrNull(record.getValue());

        // 그래프 point에는 ppm과 시각이 모두 있어야 합니다.
        if (co2Ppm == null || record.getTime() == null) {
            throw new IllegalStateException("InfluxDB CO2 조회 결과에 필요한 값이 없습니다.");
        }

        // 검증된 시각과 ppm으로 그래프 point를 반환합니다.
        return new Co2TrendItem(
                record.getTime(),
                co2Ppm
        );
    }

    // 기본 CO2 field 이름으로 숫자 값을 정수로 변환합니다.
    private Integer toIntegerOrNull(Object value) {
        return toIntegerOrNull(value, "co2_ppm");
    }

    // null을 보존하고 숫자만 반올림해 지정 field의 정수 값으로 변환합니다.
    private Integer toIntegerOrNull(Object value, String fieldName) {
        // Influx field가 없으면 Java null로 그대로 반환합니다.
        if (value == null) {
            return null;
        }
        // 숫자가 아닌 field는 계약 위반이므로 조용히 변환하지 않고 실패시킵니다.
        if (!(value instanceof Number number)) {
            throw new IllegalStateException(fieldName + " 필드를 Integer로 변환할 수 없습니다.");
        }
        // Influx의 부동소수·정수 값을 가장 가까운 Java int로 변환합니다.
        return (int) Math.round(number.doubleValue());
    }

    // 외부 node ID가 Flux 문자열 리터럴 문법을 깨지 않도록 이스케이프합니다.
    private String escapeFluxString(String value) {
        // 역슬래시를 먼저 이스케이프해 이후 큰따옴표 치환의 의미를 보존합니다.
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    // Spring 종료 전에 InfluxDB 조회 연결을 닫습니다.
    @PreDestroy
    public void close() {
        // 초기화된 클라이언트가 있을 때만 연결 자원을 해제합니다.
        if (influxDBClient != null) {
            influxDBClient.close();
        }
    }
}
