package com.airs.backend.sensor.influx;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

import com.airs.backend.sensor.config.InfluxProperties;
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

    private List<Dht22MeasurementItem> queryMeasurements(String nodeId, Instant from, Instant to) {
        if (queryApi == null) {
            throw new IllegalStateException("InfluxDB queryApi가 초기화되지 않았습니다.");
        }

        String query = """
                from(bucket: "%s")
                  |> range(start: time(v: "%s"), stop: time(v: "%s"))
                  |> filter(fn: (r) => r._measurement == "%s")
                  |> filter(fn: (r) => r.%s == "%s")
                  |> filter(fn: (r) => r._field == "temperature" or r._field == "humidity")
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
        Object temperatureValue = record.getValueByKey("temperature");
        Object humidityValue = record.getValueByKey("humidity");

        if (!(temperatureValue instanceof Number temperatureNumber)) {
            throw new IllegalStateException("temperature 필드를 Double로 변환할 수 없습니다.");
        }

        if (!(humidityValue instanceof Number humidityNumber)) {
            throw new IllegalStateException("humidity 필드를 Double로 변환할 수 없습니다.");
        }

        Double temperature = temperatureNumber.doubleValue();
        Double humidity = humidityNumber.doubleValue();

        if (record.getTime() == null) {
            throw new IllegalStateException("InfluxDB DHT22 조회 결과에 필요한 값이 없습니다.");
        }

        return new Dht22MeasurementItem(
                temperature,
                humidity,
                record.getTime()
        );
    }

    @PreDestroy
    public void close() {
        if (influxDBClient != null) {
            influxDBClient.close();
        }
    }
}
