package com.airs.backend.sensor.service;

import com.airs.backend.sensor.config.TelemetryReliabilityProperties;
import com.airs.backend.sensor.dto.TelemetryPointPayload;
import com.airs.backend.sensor.entity.TelemetryOutbox;
import com.airs.backend.sensor.influx.InfluxDht22Writer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class TelemetryOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(TelemetryOutboxPublisher.class);

    private final TelemetryReliabilityProperties properties;
    private final TelemetryOutboxStateService telemetryOutboxStateService;
    private final InfluxDht22Writer influxDht22Writer;
    private final ObjectMapper objectMapper;
    private final AtomicLong lastDatabaseFailureLogAt = new AtomicLong(0);

    @Scheduled(fixedDelayString = "${sensor.telemetry.reliability.publisher-poll-interval-millis:100}")
    public void publishScheduled() {
        if (properties.isPublisherEnabled()) {
            try {
                publishOnce();
            } catch (RuntimeException exception) {
                logDatabaseFailure(exception);
            }
        }
    }

    public int publishOnce() {
        List<Long> claimedIds = telemetryOutboxStateService.claimDue(Instant.now());
        if (!claimedIds.isEmpty()) {
            publish(claimedIds);
        }
        return claimedIds.size();
    }

    private void publish(List<Long> ids) {
        List<TelemetryOutbox> outboxes = telemetryOutboxStateService.getAll(ids);
        try {
            for (TelemetryOutbox outbox : outboxes) {
                if (outbox.getSchemaVersion() != TelemetryPointPayload.SCHEMA_VERSION) {
                    throw new IllegalStateException("지원하지 않는 telemetry outbox schema입니다: " + outbox.getSchemaVersion());
                }
            }
            List<TelemetryPointPayload> pointPayloads = outboxes.stream()
                    .map(this::deserialize)
                    .toList();
            influxDht22Writer.writeBlocking(pointPayloads);
            telemetryOutboxStateService.completeAll(ids, Instant.now());
        } catch (Exception exception) {
            telemetryOutboxStateService.retryAll(ids, Instant.now(), exception.getMessage());
            log.warn("InfluxDB telemetry outbox batch 전달에 실패했습니다. firstOutboxId={}, batchSize={}, error={}",
                    ids.getFirst(), ids.size(), exception.getMessage());
        }
    }

    private TelemetryPointPayload deserialize(TelemetryOutbox outbox) {
        try {
            return objectMapper.readValue(outbox.getPointPayloadJson(), TelemetryPointPayload.class);
        } catch (Exception exception) {
            throw new IllegalStateException("telemetry outbox payload를 읽을 수 없습니다. id=" + outbox.getId(), exception);
        }
    }

    private void logDatabaseFailure(RuntimeException exception) {
        long now = System.currentTimeMillis();
        long previous = lastDatabaseFailureLogAt.get();
        if (now - previous >= 5_000 && lastDatabaseFailureLogAt.compareAndSet(previous, now)) {
            log.warn("telemetry outbox polling에 실패했습니다. 다음 주기에 다시 시도합니다. error={}",
                    exception.getMessage());
        }
    }
}
