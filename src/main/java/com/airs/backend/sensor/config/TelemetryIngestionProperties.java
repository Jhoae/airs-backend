package com.airs.backend.sensor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

// MQTT 수신 후 telemetry 적재 작업을 분산할 실행기 설정입니다.
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sensor.ingestion")
public class TelemetryIngestionProperties {

    // 서로 다른 노드를 병렬 처리할 단일 스레드 작업자 수입니다.
    private int workerCount = 8;
    // 작업자별로 잠시 대기시킬 최대 telemetry 수입니다.
    private int queueCapacity = 2_000;
    // 종료 시 진행 중인 telemetry가 끝나기를 기다릴 최대 시간입니다.
    private int shutdownAwaitSeconds = 30;
}
