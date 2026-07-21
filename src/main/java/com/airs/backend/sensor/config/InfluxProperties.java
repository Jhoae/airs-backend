package com.airs.backend.sensor.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
// application.yaml의 influx 설정을 필드에 연결한다.
@ConfigurationProperties(prefix = "influx")
public class InfluxProperties {
    // InfluxDB HTTP API 주소다.
    private String url;
    // InfluxDB API 인증 토큰이다.
    private String token;
    // InfluxDB 조직 이름이다.
    private String org;
    // 원본 센서 데이터를 저장한 bucket 이름이다.
    private String bucket;
    // 원본 센서 measurement 이름이다.
    private String measurement;
    // 노드 ID를 저장한 tag 키 이름이다.
    private String nodeIdTag;
    // 시간 집계 데이터를 저장한 rollup bucket 이름이다.
    private String rollupBucket;
    // 시간 집계 데이터를 저장한 rollup measurement 이름이다.
    private String rollupMeasurement;
}
