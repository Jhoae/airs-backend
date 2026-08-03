package com.airs.backend.sensor.service;

import com.airs.backend.sensor.config.TelemetryReliabilityProperties;
import com.airs.backend.sensor.entity.TelemetryOutbox;
import com.airs.backend.sensor.entity.TelemetryOutboxStatus;
import com.airs.backend.sensor.repository.TelemetryOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Comparator;

@Service
@RequiredArgsConstructor
public class TelemetryOutboxStateService {

    private final TelemetryOutboxRepository telemetryOutboxRepository;
    private final TelemetryReliabilityProperties properties;

    @Transactional
    public List<Long> claimDue(Instant now) {
        Instant staleBefore = now.minusMillis(properties.getPublisherStaleClaimMillis());
        List<TelemetryOutbox> claimed = telemetryOutboxRepository.findClaimableForUpdate(
                now,
                staleBefore,
                PageRequest.of(0, properties.getPublisherBatchSize())
        );
        claimed.forEach(outbox -> outbox.claim(now));
        return claimed.stream().map(TelemetryOutbox::getId).toList();
    }

    @Transactional(readOnly = true)
    public TelemetryOutbox get(long id) {
        return telemetryOutboxRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("claim한 telemetry outbox를 찾을 수 없습니다. id=" + id));
    }

    @Transactional(readOnly = true)
    public List<TelemetryOutbox> getAll(List<Long> ids) {
        List<TelemetryOutbox> outboxes = telemetryOutboxRepository.findAllById(ids).stream()
                .sorted(Comparator.comparing(TelemetryOutbox::getId))
                .toList();
        if (outboxes.size() != ids.size()) {
            throw new IllegalStateException("claim한 telemetry outbox 일부를 찾을 수 없습니다.");
        }
        return outboxes;
    }

    @Transactional
    public void complete(long id, Instant now) {
        TelemetryOutbox outbox = telemetryOutboxRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("완료할 telemetry outbox를 찾을 수 없습니다. id=" + id));
        outbox.complete(now);
    }

    @Transactional
    public void completeAll(List<Long> ids, Instant now) {
        List<TelemetryOutbox> outboxes = telemetryOutboxRepository.findAllById(ids);
        if (outboxes.size() != ids.size()) {
            throw new IllegalStateException("완료할 telemetry outbox 일부를 찾을 수 없습니다.");
        }
        outboxes.forEach(outbox -> outbox.complete(now));
    }

    @Transactional
    public void retry(long id, Instant now, String error) {
        TelemetryOutbox outbox = telemetryOutboxRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("재시도할 telemetry outbox를 찾을 수 없습니다. id=" + id));
        long backoff = calculateBackoff(outbox.getRetryCount());
        outbox.retry(
                now.plusMillis(backoff),
                error,
                properties.getPublisherMaximumRetryCount()
        );
    }

    @Transactional
    public void retryAll(List<Long> ids, Instant now, String error) {
        List<TelemetryOutbox> outboxes = telemetryOutboxRepository.findAllById(ids);
        if (outboxes.size() != ids.size()) {
            throw new IllegalStateException("재시도할 telemetry outbox 일부를 찾을 수 없습니다.");
        }
        outboxes.forEach(outbox -> {
            long backoff = calculateBackoff(outbox.getRetryCount());
            outbox.retry(now.plusMillis(backoff), error, properties.getPublisherMaximumRetryCount());
        });
    }

    @Transactional
    public int cleanupCompleted(Instant cutoff) {
        List<Long> ids = telemetryOutboxRepository.findCompletedIdsBefore(
                TelemetryOutboxStatus.COMPLETED,
                cutoff,
                PageRequest.of(0, properties.getCleanupBatchSize())
        );
        telemetryOutboxRepository.deleteAllByIdInBatch(ids);
        return ids.size();
    }

    @Transactional
    public int cleanupDead(Instant cutoff) {
        List<Long> ids = telemetryOutboxRepository.findCreatedIdsBefore(
                TelemetryOutboxStatus.DEAD,
                cutoff,
                PageRequest.of(0, properties.getCleanupBatchSize())
        );
        telemetryOutboxRepository.deleteAllByIdInBatch(ids);
        return ids.size();
    }

    private long calculateBackoff(int retryCount) {
        int exponent = Math.min(retryCount, 20);
        long multiplier = 1L << exponent;
        long candidate;
        try {
            candidate = Math.multiplyExact(properties.getPublisherInitialBackoffMillis(), multiplier);
        } catch (ArithmeticException exception) {
            candidate = properties.getPublisherMaximumBackoffMillis();
        }
        return Math.min(candidate, properties.getPublisherMaximumBackoffMillis());
    }
}
