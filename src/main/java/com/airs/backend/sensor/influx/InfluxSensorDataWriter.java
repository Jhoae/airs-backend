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
import com.airs.backend.sensor.dto.Dht22Payload;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class InfluxSensorDataWriter {

    private static final Logger log = LoggerFactory.getLogger(InfluxSensorDataWriter.class);

    private final InfluxProperties influxProperties;
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
        if (writeApi == null) {
            throw new IllegalStateException("InfluxDB writeApi가 초기화되지 않았습니다.");
        }

        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId가 비어 있습니다.");
        }

        if (payload == null) {
            throw new IllegalArgumentException("DHT22 payload가 없습니다.");
        }

        Point point = Point.measurement(influxProperties.getMeasurement())
                .addTag(influxProperties.getNodeIdTag(), nodeId)
                .addField("temperature", payload.getTemperature())
                .addField("humidity", payload.getHumidity())
                .time(payload.getTimestamp(), WritePrecision.MS);

        writeApi.writePoint(point);
        log.debug("InfluxDB에 센서 데이터를 저장했습니다. nodeId={}", nodeId);
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
