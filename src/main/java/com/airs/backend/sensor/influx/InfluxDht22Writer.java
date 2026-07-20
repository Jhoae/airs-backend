package com.airs.backend.sensor.influx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.airs.backend.sensor.config.InfluxProperties;
import com.airs.backend.sensor.config.OccupancyProperties;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.sensor.service.OccupancyFusionResult;
import com.airs.backend.sensor.service.OccupancyFusionService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class InfluxDht22Writer {

    private static final Logger log = LoggerFactory.getLogger(InfluxDht22Writer.class);

    private final InfluxProperties influxProperties;
    private final OccupancyProperties occupancyProperties;
    private final OccupancyFusionService occupancyFusionService;
    private WriteApiBlocking writeApi;
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

        this.writeApi = influxDBClient.getWriteApiBlocking();
    }

    public void write(String nodeId, Dht22Payload payload) {
        write(nodeId, payload, occupancyFusionService.resolve(nodeId, payload));
    }

    public void write(String nodeId, Dht22Payload payload, OccupancyFusionResult occupancy) {
        if (writeApi == null) {
            throw new IllegalStateException("InfluxDB writeApi가 초기화되지 않았습니다.");
        }

        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId가 비어 있습니다.");
        }

        if (payload == null) {
            throw new IllegalArgumentException("telemetry payload가 없습니다.");
        }

        Point point = Point.measurement(influxProperties.getMeasurement())
                .addTag(influxProperties.getNodeIdTag(), nodeId)
                .addField("temperature_c", payload.getTemperature())
                .addField("humidity_pct", payload.getHumidity())
                .time(payload.getTimestamp(), WritePrecision.MS);

        if (payload.getCo2Ppm() != null) {
            point.addField("co2_ppm", payload.getCo2Ppm().doubleValue());
        }

        if (payload.getScd41Temperature() != null) {
            point.addField("scd41_temperature_c", payload.getScd41Temperature());
        }

        if (payload.getScd41Humidity() != null) {
            point.addField("scd41_humidity_pct", payload.getScd41Humidity());
        }

        if (payload.getDht22Status() != null && !payload.getDht22Status().isBlank()) {
            point.addField("dht22_status", payload.getDht22Status());
        }

        if (payload.getScd41Status() != null && !payload.getScd41Status().isBlank()) {
            point.addField("scd41_status", payload.getScd41Status());
        }

        if (occupancyProperties.isInfluxWriteEnabled()) {
            addOccupancyFields(point, payload, occupancy);
        }

        writeApi.writePoint(point);
        log.debug("InfluxDB에 센서 데이터를 저장했습니다. nodeId={}", nodeId);
    }

    private void addOccupancyFields(Point point, Dht22Payload payload, OccupancyFusionResult occupancy) {

        if (payload.getPirDetected() != null) {
            point.addField("pir_detected", payload.getPirDetected());
        }

        if (payload.getMmwaveDetected() != null) {
            point.addField("mmwave_detected", payload.getMmwaveDetected());
        }

        if (payload.getWifiSignalDbm() != null) {
            point.addField("wifi_signal_dbm", payload.getWifiSignalDbm());
        }

        if (occupancy.sourcePresent()) {
            point.addField("occupancy_state", occupancy.state().name());
        }

        if (occupancy.occupancyPresent() != null) {
            point.addField("occupancy_present", occupancy.occupancyPresent());
        }

        if (occupancy.minutesSinceMotion() != null) {
            point.addField("minutes_since_motion", occupancy.minutesSinceMotion());
        }
    }

    private void validateInfluxProperties() {
        if (isBlank(influxProperties.getUrl())) {
            throw new IllegalStateException("influx.url 설정이 비어 있습니다.");
        }

        if (isBlank(influxProperties.getToken())) {
            throw new IllegalStateException("influx.token 설정이 비어 있습니다.");
        }

        if (isBlank(influxProperties.getOrg())) {
            throw new IllegalStateException("influx.org 설정이 비어 있습니다.");
        }

        if (isBlank(influxProperties.getBucket())) {
            throw new IllegalStateException("influx.bucket 설정이 비어 있습니다.");
        }

        if (isBlank(influxProperties.getMeasurement())) {
            throw new IllegalStateException("influx.measurement 설정이 비어 있습니다.");
        }

        if (isBlank(influxProperties.getNodeIdTag())) {
            throw new IllegalStateException("influx.node-id-tag 설정이 비어 있습니다.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @PreDestroy
    public void close() {
        if (influxDBClient != null) {
            influxDBClient.close();
            log.info("InfluxDB client 연결을 종료했습니다.");
        }
    }
}
