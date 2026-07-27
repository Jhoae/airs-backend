package com.airs.backend.sensor.influx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApi;
import com.influxdb.client.WriteOptions;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.client.write.events.BackpressureEvent;
import com.influxdb.client.write.events.WriteErrorEvent;
import com.airs.backend.sensor.config.InfluxProperties;
import com.airs.backend.sensor.config.OccupancyProperties;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.sensor.service.OccupancyFusionResult;
import com.airs.backend.sensor.service.OccupancyFusionService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;

import java.time.Instant;

// MQTT telemetry를 InfluxDB raw measurement로 기록하는 컴포넌트입니다.
@Component
// 설정과 재실 융합 서비스를 생성자로 주입합니다.
@RequiredArgsConstructor
public class InfluxDht22Writer {

    // InfluxDB 연결·쓰기 실패 원인을 기록합니다.
    private static final Logger log = LoggerFactory.getLogger(InfluxDht22Writer.class);

    // InfluxDB 접속 정보와 measurement 이름을 사용합니다.
    private final InfluxProperties influxProperties;
    // 재실 관련 field를 InfluxDB에 쓸지 결정하는 설정입니다.
    private final OccupancyProperties occupancyProperties;
    // PIR·mmWave를 재실 상태로 융합하는 서비스를 사용합니다.
    private final OccupancyFusionService occupancyFusionService;
    // 여러 Point를 짧게 모아 비동기로 전송하는 InfluxDB write API입니다.
    private WriteApi writeApi;
    // 애플리케이션 생명주기 동안 유지할 InfluxDB 연결입니다.
    private InfluxDBClient influxDBClient;

    // Spring bean 생성 후 InfluxDB 연결과 write API를 초기화합니다.
    @PostConstruct
    public void init() {
        // 연결 전에 필수 InfluxDB 설정 누락을 차단합니다.
        validateInfluxProperties();

        // URL·토큰·organization·기본 bucket으로 InfluxDB 클라이언트를 생성합니다.
        influxDBClient = InfluxDBClientFactory.create(
                influxProperties.getUrl(),
                influxProperties.getToken().toCharArray(),
                influxProperties.getOrg(),
                influxProperties.getBucket()
        );

        // MQTT 수신 스레드를 막지 않도록 Point를 배치로 전송할 쓰기 옵션을 만듭니다.
        WriteOptions writeOptions = WriteOptions.builder()
                // 짧은 burst를 하나의 HTTP 요청으로 합칠 최대 Point 수를 설정합니다.
                .batchSize(influxProperties.getWriteBatchSize())
                // batch가 덜 차도 이 시간 안에는 InfluxDB로 전송합니다.
                .flushInterval(influxProperties.getWriteFlushIntervalMillis())
                // InfluxDB 일시 지연 동안 메모리에 보관할 최대 Point 수를 설정합니다.
                .bufferLimit(influxProperties.getWriteBufferLimit())
                // 설정한 배치 쓰기 정책을 완성합니다.
                .build();

        // 비동기 batch write API를 생성해 telemetry 콜백의 동기 HTTP 대기를 제거합니다.
        this.writeApi = influxDBClient.makeWriteApi(writeOptions);
        // 비동기 전송 실패는 호출자에게 전파되지 않으므로 원인을 반드시 운영 로그에 남깁니다.
        this.writeApi.listenEvents(WriteErrorEvent.class,
                event -> log.warn("InfluxDB 비동기 batch 저장에 실패했습니다. error={}", event.getThrowable().getMessage(), event.getThrowable()));
        // 메모리 버퍼가 넘치면 데이터 유실 위험이 있으므로 즉시 경고를 남깁니다.
        this.writeApi.listenEvents(BackpressureEvent.class,
                event -> log.warn("InfluxDB 비동기 batch 버퍼 압력이 발생했습니다. reason={}", event.getReason()));
    }

    // 재실 결과가 없는 호출에서는 현재 telemetry로 재실 상태를 먼저 계산합니다.
    public void write(String nodeId, Dht22Payload payload) {
        // 계산한 재실 결과와 함께 실제 Point 쓰기 메서드를 호출합니다.
        write(nodeId, payload, occupancyFusionService.resolve(nodeId, payload));
    }

    // 하나의 telemetry를 센서·상태 field가 포함된 raw Point로 기록합니다.
    public void write(String nodeId, Dht22Payload payload, OccupancyFusionResult occupancy) {
        // 초기화 전 쓰기를 막아 잘못된 런타임 상태를 드러냅니다.
        if (writeApi == null) {
            throw new IllegalStateException("InfluxDB writeApi가 초기화되지 않았습니다.");
        }

        // node ID가 없으면 시계열 데이터를 노드별로 구분할 수 없습니다.
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId가 비어 있습니다.");
        }

        // telemetry 본문이 없으면 기록할 센서 field가 없습니다.
        if (payload == null) {
            throw new IllegalArgumentException("telemetry payload가 없습니다.");
        }

        // measurement·node tag·기본 DHT22 field·센서 수신 시각으로 raw Point를 만듭니다.
        Point point = Point.measurement(influxProperties.getMeasurement())
                .addTag(influxProperties.getNodeIdTag(), nodeId)
                .addField("temperature_c", payload.getTemperature())
                .addField("humidity_pct", payload.getHumidity())
                .time(payload.getTimestamp(), WritePrecision.MS);

        // CO2 값이 수신된 경우에만 숫자 field로 추가합니다.
        if (payload.getCo2Ppm() != null) {
            point.addField("co2_ppm", payload.getCo2Ppm().doubleValue());
        }

        // SCD41 온도가 있으면 DHT22 온도와 별도 field로 기록합니다.
        if (payload.getScd41Temperature() != null) {
            point.addField("scd41_temperature_c", payload.getScd41Temperature());
        }

        // SCD41 습도가 있으면 DHT22 습도와 별도 field로 기록합니다.
        if (payload.getScd41Humidity() != null) {
            point.addField("scd41_humidity_pct", payload.getScd41Humidity());
        }

        // 비어 있지 않은 DHT22 상태만 문자열 field로 보존합니다.
        if (payload.getDht22Status() != null && !payload.getDht22Status().isBlank()) {
            point.addField("dht22_status", payload.getDht22Status());
        }

        // 비어 있지 않은 SCD41 상태만 문자열 field로 보존합니다.
        if (payload.getScd41Status() != null && !payload.getScd41Status().isBlank()) {
            point.addField("scd41_status", payload.getScd41Status());
        }

        // 설정이 켜진 환경에서만 재실·Wi-Fi 파생 field를 같은 Point에 추가합니다.
        if (occupancyProperties.isInfluxWriteEnabled()) {
            addOccupancyFields(point, payload, occupancy);
        }

        // 완성된 Point를 기본 raw bucket의 비동기 batch 대기열에 넣습니다.
        writeApi.writePoint(point);
        // 운영 로그에서 저장한 node ID를 추적할 수 있게 남깁니다.
        log.debug("InfluxDB에 센서 데이터를 저장했습니다. nodeId={}", nodeId);
    }

    // 10분 규칙 평가가 계산한 Comfort Score를 해당 평가 시각의 raw field로 기록합니다.
    public void writeComfortScore(String nodeId, int comfortScore, Instant evaluatedAt) {
        // 초기화 전 쓰기를 막아 잘못된 런타임 상태를 드러냅니다.
        if (writeApi == null) {
            throw new IllegalStateException("InfluxDB writeApi가 초기화되지 않았습니다.");
        }

        // node ID가 없으면 점수를 어떤 노드의 이력으로 저장할지 알 수 없습니다.
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId가 비어 있습니다.");
        }

        // Comfort Score는 정책상 0점부터 100점 사이여야 합니다.
        if (comfortScore < 0 || comfortScore > 100) {
            throw new IllegalArgumentException("comfortScore는 0에서 100 사이여야 합니다.");
        }

        // 평가 시각이 없으면 그래프에서 점수의 시간 위치를 정할 수 없습니다.
        if (evaluatedAt == null) {
            throw new IllegalArgumentException("evaluatedAt이 비어 있습니다.");
        }

        // 기존 raw telemetry와 같은 measurement·node tag로 점수 field만 가진 Point를 만듭니다.
        Point point = Point.measurement(influxProperties.getMeasurement())
                .addTag(influxProperties.getNodeIdTag(), nodeId)
                .addField("comfort_score", comfortScore)
                .time(evaluatedAt, WritePrecision.MS);

        // MySQL 최신 snapshot과 별도로 그래프에 쓸 사실 기반 이력을 비동기 대기열에 넣습니다.
        writeApi.writePoint(point);
        // 운영 중 점수 이력 적재 여부를 node ID와 함께 추적합니다.
        log.debug("InfluxDB에 Comfort Score를 저장했습니다. nodeId={}, comfortScore={}", nodeId, comfortScore);
    }

    // 원본 센서값과 융합 결과에서 재실·Wi-Fi 관련 field를 Point에 추가합니다.
    private void addOccupancyFields(Point point, Dht22Payload payload, OccupancyFusionResult occupancy) {

        // PIR 값이 수신된 경우에만 정수 field로 저장합니다.
        if (payload.getPirDetected() != null) {
            point.addField("pir_detected", payload.getPirDetected());
        }

        // mmWave 값이 수신된 경우에만 정수 field로 저장합니다.
        if (payload.getMmwaveDetected() != null) {
            point.addField("mmwave_detected", payload.getMmwaveDetected());
        }

        // Wi-Fi RSSI가 수신된 경우에만 dBm field로 저장합니다.
        if (payload.getWifiSignalDbm() != null) {
            point.addField("wifi_signal_dbm", payload.getWifiSignalDbm());
        }

        // 판단 근거가 있는 경우에만 PRESENT·ABSENT·UNKNOWN 상태를 기록합니다.
        if (occupancy.sourcePresent()) {
            point.addField("occupancy_state", occupancy.state().name());
        }

        // boolean 재실 여부가 결정된 경우에만 정수 field로 저장합니다.
        if (occupancy.occupancyPresent() != null) {
            point.addField("occupancy_present", occupancy.occupancyPresent());
        }

        // 마지막 움직임 이후 시간이 계산된 경우에만 분 단위 field로 저장합니다.
        if (occupancy.minutesSinceMotion() != null) {
            point.addField("minutes_since_motion", occupancy.minutesSinceMotion());
        }
    }

    // InfluxDB 연결과 시계열 식별에 필요한 설정을 모두 검증합니다.
    private void validateInfluxProperties() {
        // URL이 없으면 InfluxDB 서버에 연결할 수 없습니다.
        if (isBlank(influxProperties.getUrl())) {
            throw new IllegalStateException("influx.url 설정이 비어 있습니다.");
        }

        // 토큰이 없으면 InfluxDB 쓰기 권한을 인증할 수 없습니다.
        if (isBlank(influxProperties.getToken())) {
            throw new IllegalStateException("influx.token 설정이 비어 있습니다.");
        }

        // organization이 없으면 InfluxDB 리소스 범위를 결정할 수 없습니다.
        if (isBlank(influxProperties.getOrg())) {
            throw new IllegalStateException("influx.org 설정이 비어 있습니다.");
        }

        // bucket이 없으면 raw Point를 저장할 위치가 없습니다.
        if (isBlank(influxProperties.getBucket())) {
            throw new IllegalStateException("influx.bucket 설정이 비어 있습니다.");
        }

        // measurement가 없으면 시계열 데이터 종류를 식별할 수 없습니다.
        if (isBlank(influxProperties.getMeasurement())) {
            throw new IllegalStateException("influx.measurement 설정이 비어 있습니다.");
        }

        // node tag 이름이 없으면 노드별 조회와 집계가 불가능합니다.
        if (isBlank(influxProperties.getNodeIdTag())) {
            throw new IllegalStateException("influx.node-id-tag 설정이 비어 있습니다.");
        }
    }

    // null과 공백 문자열을 같은 설정 누락 상태로 판단합니다.
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // Spring 종료 전에 비동기 버퍼와 InfluxDB 연결 자원을 닫습니다.
    @PreDestroy
    public void close() {
        // 메모리에 남은 Point를 전송하고 비동기 write API를 닫습니다.
        if (writeApi != null) {
            writeApi.close();
        }
        // 초기화된 클라이언트가 있을 때만 안전하게 연결을 종료합니다.
        if (influxDBClient != null) {
            influxDBClient.close();
            log.info("InfluxDB client 연결을 종료했습니다.");
        }
    }
}
