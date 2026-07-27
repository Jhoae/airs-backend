package com.airs.backend.sensor.dto;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
// 알 수 없는 telemetry 필드는 무시해 펌웨어 확장과 호환한다.
@JsonIgnoreProperties(ignoreUnknown = true)
// MQTT telemetry JSON을 Spring 내부 센서 payload로 역직렬화한다.
public class Dht22Payload {

    // JSON temperature_c를 내부 온도 필드로 읽는다.
    @JsonAlias("temperature_c")
    private Double temperature;
    // JSON humidity_pct를 내부 습도 필드로 읽는다.
    @JsonAlias("humidity_pct")
    private Double humidity;
    // JSON co2_ppm을 내부 CO2 필드로 읽는다.
    @JsonAlias("co2_ppm")
    private Integer co2Ppm;
    // SCD41이 별도로 측정한 온도를 읽는다.
    @JsonAlias("scd41_temperature_c")
    private Double scd41Temperature;
    // SCD41이 별도로 측정한 습도를 읽는다.
    @JsonAlias("scd41_humidity_pct")
    private Double scd41Humidity;
    // 각 센서의 정상 여부를 담은 중첩 JSON을 읽는다.
    @JsonAlias("sensor_status")
    private TelemetrySensorStatus sensorStatus;
    // PIR 감지값 0 또는 1을 읽는다.
    @JsonAlias("pir_detected")
    private Integer pirDetected;
    // mmWave 감지값 0 또는 1을 읽는다.
    @JsonAlias("mmwave_detected")
    private Integer mmwaveDetected;
    // Wi-Fi RSSI(dBm)를 읽는다.
    @JsonAlias("wifi_signal_dbm")
    private Integer wifiSignalDbm;
    // 펌웨어 재부팅마다 바뀌는 발행 세션 식별자를 읽는다.
    @JsonAlias("boot_id")
    private String bootId;
    // 같은 부팅 세션 안에서 증가하는 telemetry 순번을 읽는다.
    @JsonAlias("sequence_no")
    private Long sequenceNo;
    // MQTT에 시각이 없을 때 수신 서비스가 보정한 원본 시각이다.
    private Instant timestamp;

    // 기존 온습도 전용 입력을 만들기 위한 생성자다.
    public Dht22Payload(Double temperature, Double humidity, Instant timestamp) {
        // 온도 값을 저장한다.
        this.temperature = temperature;
        // 습도 값을 저장한다.
        this.humidity = humidity;
        // 측정 시각을 저장한다.
        this.timestamp = timestamp;
    }

    // 온습도와 CO2를 함께 가진 입력을 만들기 위한 생성자다.
    public Dht22Payload(Double temperature, Double humidity, Integer co2Ppm, Instant timestamp) {
        // 온도 값을 저장한다.
        this.temperature = temperature;
        // 습도 값을 저장한다.
        this.humidity = humidity;
        // CO2 값을 저장한다.
        this.co2Ppm = co2Ppm;
        // 측정 시각을 저장한다.
        this.timestamp = timestamp;
    }

    // 중첩 상태 객체에서 DHT22 상태만 안전하게 꺼낸다.
    public String getDht22Status() {
        // 상태 객체가 없으면 센서 상태도 null로 보존한다.
        return sensorStatus == null ? null : sensorStatus.getDht22();
    }

    // 중첩 상태 객체에서 SCD41 상태만 안전하게 꺼낸다.
    public String getScd41Status() {
        // 상태 객체가 없으면 센서 상태도 null로 보존한다.
        return sensorStatus == null ? null : sensorStatus.getScd41();
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    // 알 수 없는 센서 상태 필드는 무시해 펌웨어 확장과 호환한다.
    @JsonIgnoreProperties(ignoreUnknown = true)
    // MQTT sensor_status 중첩 객체를 표현한다.
    public static class TelemetrySensorStatus {

        // DHT22 센서의 장치 상태 문자열이다.
        private String dht22;
        // SCD41 센서의 장치 상태 문자열이다.
        private String scd41;
    }
}
