package com.airs.backend.sensor.service;

import com.airs.backend.sensor.config.TelemetryReliabilityProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class TelemetryOutboxCleanupService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryOutboxCleanupService.class);

    private final TelemetryReliabilityProperties properties;
    private final TelemetryOutboxStateService telemetryOutboxStateService;

    @Scheduled(fixedDelayString = "${sensor.telemetry.reliability.cleanup-interval-millis:5000}")
    public void cleanup() {
        if (!properties.isCleanupEnabled()) {
            return;
        }
        Instant now = Instant.now();
        int completed = telemetryOutboxStateService.cleanupCompleted(
                now.minusMillis(properties.getCompletedRetentionMillis())
        );
        int dead = telemetryOutboxStateService.cleanupDead(
                now.minusMillis(properties.getDeadRetentionMillis())
        );
        if (completed > 0 || dead > 0) {
            log.info("telemetry outbox 보관 기한이 지난 행을 정리했습니다. completed={}, dead={}", completed, dead);
        }
    }
}
