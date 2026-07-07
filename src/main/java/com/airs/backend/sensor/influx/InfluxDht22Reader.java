package com.airs.backend.sensor.influx;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

import com.airs.backend.sensor.config.InfluxProperties;
import com.airs.backend.sensor.dto.Co2TrendItem;
import com.airs.backend.sensor.dto.DailyDht22SummaryResponse;
import com.airs.backend.sensor.dto.Dht22MeasurementItem;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;

import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class InfluxDht22Reader {

    private final InfluxProperties influxProperties;
    private InfluxDBClient influxDBClient;
    private QueryApi queryApi;

    @PostConstruct
    public void init() {
        if (influxProperties.getUrl() == null || influxProperties.getUrl().isBlank()) {
            throw new IllegalStateException("influx.url 설정이 비어 있습니다.");
        }

        if (influxProperties.getToken() == null || influxProperties.getToken().isBlank()) {
            throw new IllegalStateException("influx.token 설정이 비어 있습니다.");
        }

        if (influxProperties.getOrg() == null || influxProperties.getOrg().isBlank()) {
            throw new IllegalStateException("influx.org 설정이 비어 있습니다.");
        }

        if (influxProperties.getBucket() == null || influxProperties.getBucket().isBlank()) {
            throw new IllegalStateException("influx.bucket 설정이 비어 있습니다.");
        }

        if (influxProperties.getMeasurement() == null || influxProperties.getMeasurement().isBlank()) {
            throw new IllegalStateException("influx.measurement 설정이 비어 있습니다.");
        }

        if (influxProperties.getNodeIdTag() == null || influxProperties.getNodeIdTag().isBlank()) {
            throw new IllegalStateException("influx.node-id-tag 설정이 비어 있습니다.");
        }

        influxDBClient = InfluxDBClientFactory.create(
                influxProperties.getUrl(),
                influxProperties.getToken().toCharArray(),
                influxProperties.getOrg(),
                influxProperties.getBucket()
        );
        queryApi = influxDBClient.getQueryApi();
    }


    // for React
    public DailyDht22SummaryResponse readDailySummary(String nodeId, LocalDate date) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId가 비어 있습니다.");
        }

        if (date == null) {
            throw new IllegalArgumentException("date가 비어 있습니다.");
        }

        Instant from = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<Dht22MeasurementItem> measurements = queryMeasurements(nodeId, from, to);

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

        Dht22MeasurementItem peakTemperatureItem = measurements.stream()
                .max(Comparator.comparing(item -> item.getTemperature()))
                .orElseThrow();

        Dht22MeasurementItem minTemperatureItem = measurements.stream()
                .min(Comparator.comparing(item -> item.getTemperature()))
                .orElseThrow();

        Dht22MeasurementItem peakHumidityItem = measurements.stream()
                .max(Comparator.comparing(item -> item.getHumidity()))
                .orElseThrow();

        Dht22MeasurementItem minHumidityItem = measurements.stream()
                .min(Comparator.comparing(item -> item.getHumidity()))
                .orElseThrow();

        double averageTemperature = measurements.stream()
                .map(item -> item.getTemperature())
                .filter(value -> value != null)
                .mapToDouble(value -> value.doubleValue())
                .average()
                .orElseThrow();

        double averageHumidity = measurements.stream()
                .map(item -> item.getHumidity())
                .filter(value -> value != null)
                .mapToDouble(value -> value.doubleValue())
                .average()
                .orElseThrow();

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

    // for AI parameter
    public List<Dht22MeasurementItem> readRange(String nodeId, Instant from, Instant to) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId가 비어 있습니다.");
        }

        if (from == null) {
            throw new IllegalArgumentException("from이 비어 있습니다.");
        }

        if (to == null) {
            throw new IllegalArgumentException("to가 비어 있습니다.");
        }

        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from은 to보다 이후일 수 없습니다.");
        }

        return queryMeasurements(nodeId, from, to);
    }


    // 노드 상세 페이지
    public List<Co2TrendItem> readCo2Trend(String nodeId, Instant from, Instant to, String window) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId가 비어 있습니다.");
        }

        if (from == null) {
            throw new IllegalArgumentException("from이 비어 있습니다.");
        }

        if (to == null) {
            throw new IllegalArgumentException("to가 비어 있습니다.");
        }

        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from은 to보다 이후일 수 없습니다.");
        }

        if (window == null || !window.matches("\\d+[smhd]")) {
            throw new IllegalArgumentException("window 형식이 올바르지 않습니다.");
        }

        if (queryApi == null) {
            throw new IllegalStateException("InfluxDB queryApi가 초기화되지 않았습니다.");
        }

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

        List<FluxTable> tables = queryApi.query(query, influxProperties.getOrg());

        return tables.stream()
                .flatMap(table -> table.getRecords().stream())
                .map(record -> toCo2TrendItem(record))
                .toList();
    }

    public List<Co2TrendItem> readAverageCo2Trend(List<String> nodeIds, Instant from, Instant to, String window) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            throw new IllegalArgumentException("nodeIds가 비어 있습니다.");
        }

        if (nodeIds.stream().anyMatch(nodeId -> nodeId == null || nodeId.isBlank())) {
            throw new IllegalArgumentException("nodeIds에 비어 있는 nodeId가 포함되어 있습니다.");
        }

        if (from == null) {
            throw new IllegalArgumentException("from이 비어 있습니다.");
        }

        if (to == null) {
            throw new IllegalArgumentException("to가 비어 있습니다.");
        }

        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from은 to보다 이후일 수 없습니다.");
        }

        if (window == null || !window.matches("\\d+[smhd]")) {
            throw new IllegalArgumentException("window 형식이 올바르지 않습니다.");
        }

        if (queryApi == null) {
            throw new IllegalStateException("InfluxDB queryApi가 초기화되지 않았습니다.");
        }

        String nodeIdSet = nodeIds.stream()
                .distinct()
                .map(nodeId -> "\"" + escapeFluxString(nodeId) + "\"")
                .collect(java.util.stream.Collectors.joining(", "));

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

        List<FluxTable> tables = queryApi.query(query, influxProperties.getOrg());

        return tables.stream()
                .flatMap(table -> table.getRecords().stream())
                .map(record -> toCo2TrendItem(record))
                .toList();
    }

    private List<Dht22MeasurementItem> queryMeasurements(String nodeId, Instant from, Instant to) {
        if (queryApi == null) {
            throw new IllegalStateException("InfluxDB queryApi가 초기화되지 않았습니다.");
        }

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

        List<FluxTable> tables = queryApi.query(query, influxProperties.getOrg());

        return tables.stream()
                .flatMap(table -> table.getRecords().stream())
                .map(record -> toMeasurementItem(record))
                .toList();
    }

    private Dht22MeasurementItem toMeasurementItem(FluxRecord record) {
        Object temperatureValue = record.getValueByKey("temperature_c");
        Object humidityValue = record.getValueByKey("humidity_pct");
        Object co2Value = record.getValueByKey("co2_ppm");

        if (!(temperatureValue instanceof Number temperatureNumber)) {
            throw new IllegalStateException("temperature_c 필드를 Double로 변환할 수 없습니다.");
        }

        if (!(humidityValue instanceof Number humidityNumber)) {
            throw new IllegalStateException("humidity_pct 필드를 Double로 변환할 수 없습니다.");
        }

        Double temperature = temperatureNumber.doubleValue();
        Double humidity = humidityNumber.doubleValue();
        Integer co2Ppm = toIntegerOrNull(co2Value);

        if (record.getTime() == null) {
            throw new IllegalStateException("InfluxDB DHT22 조회 결과에 필요한 값이 없습니다.");
        }

        return new Dht22MeasurementItem(
                temperature,
                humidity,
                co2Ppm,
                record.getTime()
        );
    }

    private Co2TrendItem toCo2TrendItem(FluxRecord record) {
        Integer co2Ppm = toIntegerOrNull(record.getValue());

        if (co2Ppm == null || record.getTime() == null) {
            throw new IllegalStateException("InfluxDB CO2 조회 결과에 필요한 값이 없습니다.");
        }

        return new Co2TrendItem(
                record.getTime(),
                co2Ppm
        );
    }

    private Integer toIntegerOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("co2_ppm 필드를 Integer로 변환할 수 없습니다.");
        }
        return (int) Math.round(number.doubleValue());
    }

    private String escapeFluxString(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    @PreDestroy
    public void close() {
        if (influxDBClient != null) {
            influxDBClient.close();
        }
    }
}
