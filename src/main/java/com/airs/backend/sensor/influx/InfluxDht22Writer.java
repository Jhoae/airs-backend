package com.airs.backend.sensor.influx;

import com.airs.backend.sensor.config.InfluxProperties;
import com.airs.backend.sensor.config.OccupancyProperties;
import com.airs.backend.sensor.dto.TelemetryPointPayload;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApi;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.WriteOptions;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.client.write.events.BackpressureEvent;
import com.influxdb.client.write.events.WriteErrorEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class InfluxDht22Writer {

    private static final Logger log = LoggerFactory.getLogger(InfluxDht22Writer.class);

    private final InfluxProperties influxProperties;
    private final OccupancyProperties occupancyProperties;
    private WriteApi asyncWriteApi;
    private WriteApiBlocking blockingWriteApi;
    private InfluxDBClient influxDBClient;

    @PostConstruct
    public void init() {
        validateInfluxProperties();
        influxDBClient = InfluxDBClientFactory.create(
                influxProperties.getUrl(),
                influxProperties.getToken().toCharArray(),
                influxProperties.getOrg(),
                influxProperties.getBucket()
        );

        WriteOptions writeOptions = WriteOptions.builder()
                .batchSize(influxProperties.getWriteBatchSize())
                .flushInterval(influxProperties.getWriteFlushIntervalMillis())
                .bufferLimit(influxProperties.getWriteBufferLimit())
                .build();
        asyncWriteApi = influxDBClient.makeWriteApi(writeOptions);
        blockingWriteApi = influxDBClient.getWriteApiBlocking();
        asyncWriteApi.listenEvents(WriteErrorEvent.class,
                event -> log.warn("InfluxDB 비동기 batch 저장에 실패했습니다. error={}",
                        event.getThrowable().getMessage(), event.getThrowable()));
        asyncWriteApi.listenEvents(BackpressureEvent.class,
                event -> log.warn("InfluxDB 비동기 batch 버퍼 압력이 발생했습니다. reason={}", event.getReason()));
    }

    // outbox publisher는 HTTP 쓰기 성공·실패를 호출자에게 돌려주는 blocking API를 사용한다.
    public void writeBlocking(TelemetryPointPayload payload) {
        if (blockingWriteApi == null) {
            throw new IllegalStateException("InfluxDB blockingWriteApi가 초기화되지 않았습니다.");
        }
        blockingWriteApi.writePoint(toPoint(payload));
    }

    // outbox batch를 한 HTTP 요청으로 보내 건별 blocking 호출의 네트워크 왕복을 줄인다.
    public void writeBlocking(List<TelemetryPointPayload> payloads) {
        if (blockingWriteApi == null) {
            throw new IllegalStateException("InfluxDB blockingWriteApi가 초기화되지 않았습니다.");
        }
        if (payloads.isEmpty()) {
            return;
        }
        blockingWriteApi.writePoints(payloads.stream().map(this::toPoint).toList());
    }

    public void writeComfortScore(String nodeId, int comfortScore, Instant evaluatedAt) {
        if (asyncWriteApi == null) {
            throw new IllegalStateException("InfluxDB writeApi가 초기화되지 않았습니다.");
        }
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId가 비어 있습니다.");
        }
        if (comfortScore < 0 || comfortScore > 100) {
            throw new IllegalArgumentException("comfortScore는 0에서 100 사이여야 합니다.");
        }
        if (evaluatedAt == null) {
            throw new IllegalArgumentException("evaluatedAt이 비어 있습니다.");
        }

        Point point = Point.measurement(influxProperties.getMeasurement())
                .addTag(influxProperties.getNodeIdTag(), nodeId)
                .addField("comfort_score", comfortScore)
                .time(evaluatedAt, WritePrecision.MS);
        asyncWriteApi.writePoint(point);
    }

    Point toPoint(TelemetryPointPayload payload) {
        Point point = Point.measurement(influxProperties.getMeasurement())
                .addTag(influxProperties.getNodeIdTag(), payload.nodeId())
                .addField("temperature_c", payload.temperature())
                .addField("humidity_pct", payload.humidity())
                .addField("boot_id", payload.bootId())
                .addField("sequence_no", payload.sequenceNo())
                .addField("received_at_epoch_ms", payload.receivedAt().toEpochMilli())
                .addField("ingest_delay_ms", payload.ingestDelayMillis())
                .addField("delivery_decision", payload.deliveryDecision())
                // 센서 그래프와 변화량은 서버 도착 시각이 아닌 실제 측정 시각을 사용한다.
                .time(payload.observedAt(), WritePrecision.NS);

        addField(point, "co2_ppm", payload.co2Ppm() == null ? null : payload.co2Ppm().doubleValue());
        addField(point, "scd41_temperature_c", payload.scd41Temperature());
        addField(point, "scd41_humidity_pct", payload.scd41Humidity());
        addField(point, "dht22_status", payload.dht22Status());
        addField(point, "scd41_status", payload.scd41Status());
        if (occupancyProperties.isInfluxWriteEnabled()) {
            addField(point, "pir_detected", payload.pirDetected());
            addField(point, "mmwave_detected", payload.mmwaveDetected());
            addField(point, "wifi_signal_dbm", payload.wifiSignalDbm());
            addField(point, "occupancy_state", payload.occupancyState());
            addField(point, "occupancy_present", payload.occupancyPresent());
            addField(point, "minutes_since_motion", payload.minutesSinceMotion());
        }
        return point;
    }

    private void addField(Point point, String name, Object value) {
        if (value instanceof Number number) {
            point.addField(name, number);
        } else if (value instanceof Boolean bool) {
            point.addField(name, bool);
        } else if (value instanceof String text && !text.isBlank()) {
            point.addField(name, text);
        }
    }

    private void validateInfluxProperties() {
        if (isBlank(influxProperties.getUrl())
                || isBlank(influxProperties.getToken())
                || isBlank(influxProperties.getOrg())
                || isBlank(influxProperties.getBucket())
                || isBlank(influxProperties.getMeasurement())
                || isBlank(influxProperties.getNodeIdTag())) {
            throw new IllegalStateException("InfluxDB 필수 설정이 비어 있습니다.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @PreDestroy
    public void close() {
        if (asyncWriteApi != null) {
            asyncWriteApi.close();
        }
        if (influxDBClient != null) {
            influxDBClient.close();
        }
    }
}
