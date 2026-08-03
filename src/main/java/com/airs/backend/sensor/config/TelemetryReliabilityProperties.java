package com.airs.backend.sensor.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sensor.telemetry.reliability")
public class TelemetryReliabilityProperties {

    private long mysqlRetryInitialBackoffMillis = 100;
    private long mysqlRetryMaximumBackoffMillis = 5_000;
    // 격리 staging에서 commit과 ACK 사이 강제 종료를 재현하기 위한 지연값이며 운영 기본은 0이다.
    private long ackDelayMillis = 0;
    private boolean publisherEnabled = true;
    private int publisherBatchSize = 200;
    private long publisherPollIntervalMillis = 100;
    private long publisherInitialBackoffMillis = 1_000;
    private long publisherMaximumBackoffMillis = 60_000;
    private int publisherMaximumRetryCount = 10;
    private long publisherStaleClaimMillis = 30_000;
    private boolean cleanupEnabled = true;
    private long cleanupIntervalMillis = 5_000;
    private long completedRetentionMillis = 600_000;
    private long deadRetentionMillis = 2_592_000_000L;
    private int cleanupBatchSize = 2_000;
}
