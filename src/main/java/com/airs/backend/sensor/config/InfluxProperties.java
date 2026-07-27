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
    // 비동기 쓰기 요청 하나에 모을 최대 Point 수다.
    private int writeBatchSize = 500;
    // Point가 적어도 강제로 전송할 최대 대기 시간(ms)이다.
    private int writeFlushIntervalMillis = 1000;
    // InfluxDB가 잠시 느려도 보관할 비동기 쓰기 대기열 크기다.
    private int writeBufferLimit = 20_000;
}
