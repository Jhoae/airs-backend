package com.airs.backend.sensor.service;

import com.airs.backend.sensor.config.TelemetryIngestionProperties;
import com.airs.backend.sensor.config.TelemetryReliabilityProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class TelemetryIngestionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestionDispatcher.class);

    private final TelemetryIngestionProperties properties;
    private final TelemetryReliabilityProperties reliabilityProperties;
    private final Dht22IngestionService dht22IngestionService;
    private final MeterRegistry meterRegistry;
    private final List<ThreadPoolExecutor> executors = new ArrayList<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    @PostConstruct
    public void initialize() {
        validateProperties();
        for (int index = 0; index < properties.getWorkerCount(); index++) {
            ThreadPoolExecutor executor = createExecutor(index);
            executors.add(executor);
            meterRegistry.gaugeCollectionSize(
                    "airs.telemetry.ingestion.queue.depth",
                    Tags.of("stripe", Integer.toString(index)),
                    executor.getQueue()
            );
        }
        log.info("telemetry 적재 분배기를 시작했습니다. workerCount={}, queueCapacity={}",
                properties.getWorkerCount(), properties.getQueueCapacity());
    }

    public void dispatch(TelemetryIngestionCommand command) {
        if (executors.isEmpty()) {
            throw new IllegalStateException("telemetry 적재 분배기가 초기화되지 않았습니다.");
        }
        if (!accepting.get()) {
            throw new RejectedExecutionException("telemetry 적재 분배기가 종료 중입니다.");
        }

        ThreadPoolExecutor executor = executors.get(
                Math.floorMod(command.nodeId().hashCode(), executors.size())
        );
        executor.execute(() -> ingestUntilCommitted(command));
    }

    private void ingestUntilCommitted(TelemetryIngestionCommand command) {
        long backoffMillis = reliabilityProperties.getMysqlRetryInitialBackoffMillis();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                TelemetryDeliveryDecision decision = dht22IngestionService.ingest(
                        command.nodeId(),
                        command.payload(),
                        command.receivedAt()
                );
                meterRegistry.counter(
                        "airs.telemetry.ingestion.decision",
                        "decision",
                        decision.name().toLowerCase()
                ).increment();
                if (!waitBeforeAcknowledgment()) {
                    return;
                }
                acknowledge(command, decision);
                return;
            } catch (DataAccessException | TransactionException exception) {
                meterRegistry.counter("airs.telemetry.ingestion.mysql.retry").increment();
                log.warn("MySQL telemetry transaction에 실패해 같은 stripe에서 재시도합니다. nodeId={}, messageId={}, backoffMs={}, error={}",
                        command.nodeId(), command.mqttMessageId(), backoffMillis, exception.getMessage());
                if (!waitBackoff(backoffMillis)) {
                    return;
                }
                backoffMillis = Math.min(
                        backoffMillis * 2,
                        reliabilityProperties.getMysqlRetryMaximumBackoffMillis()
                );
            } catch (RuntimeException exception) {
                // 입력 검증 이후 내부 오류도 다음 sequence로 넘어가지 않고 같은 stripe에서 재시도한다.
                meterRegistry.counter("airs.telemetry.ingestion.internal.retry").increment();
                log.error("telemetry 내부 처리에 실패해 같은 stripe에서 재시도합니다. nodeId={}, messageId={}, backoffMs={}",
                        command.nodeId(), command.mqttMessageId(), backoffMillis, exception);
                if (!waitBackoff(backoffMillis)) {
                    return;
                }
                backoffMillis = Math.min(
                        backoffMillis * 2,
                        reliabilityProperties.getMysqlRetryMaximumBackoffMillis()
                );
            }
        }
    }

    private void acknowledge(TelemetryIngestionCommand command, TelemetryDeliveryDecision decision) {
        try {
            command.acknowledgment().complete();
            log.debug("MySQL transaction 뒤 MQTT ACK를 완료했습니다. nodeId={}, messageId={}, qos={}, decision={}",
                    command.nodeId(), command.mqttMessageId(), command.mqttQos(), decision);
        } catch (Exception exception) {
            // DB commit 뒤 ACK만 실패하면 broker가 재전달하고 durable sequence가 중복을 차단한다.
            log.warn("MySQL transaction 뒤 MQTT ACK에 실패했습니다. nodeId={}, messageId={}, error={}",
                    command.nodeId(), command.mqttMessageId(), exception.getMessage(), exception);
        }
    }

    private boolean waitBackoff(long backoffMillis) {
        try {
            Thread.sleep(backoffMillis);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean waitBeforeAcknowledgment() {
        if (reliabilityProperties.getAckDelayMillis() == 0) {
            return true;
        }
        return waitBackoff(reliabilityProperties.getAckDelayMillis());
    }

    private ThreadPoolExecutor createExecutor(int index) {
        ThreadFactory threadFactory = Thread.ofPlatform()
                .name("telemetry-ingestion-" + index + "-", 0)
                .factory();
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()),
                threadFactory,
                (task, executor) -> blockUntilQueued(index, task, executor)
        );
    }

    private void blockUntilQueued(int stripe, Runnable task, ThreadPoolExecutor executor) {
        if (executor.isShutdown()) {
            throw new RejectedExecutionException("telemetry 적재 분배기가 종료 중입니다.");
        }
        long startedAt = System.nanoTime();
        try {
            executor.getQueue().put(task);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RejectedExecutionException("telemetry 적재 대기 중 인터럽트되었습니다.", exception);
        } finally {
            meterRegistry.timer(
                    "airs.telemetry.ingestion.queue.wait",
                    "stripe",
                    Integer.toString(stripe)
            ).record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }

    private void validateProperties() {
        if (properties.getWorkerCount() <= 0 || properties.getQueueCapacity() <= 0) {
            throw new IllegalStateException("telemetry worker와 queue 크기는 1 이상이어야 합니다.");
        }
        if (reliabilityProperties.getMysqlRetryInitialBackoffMillis() <= 0
                || reliabilityProperties.getMysqlRetryMaximumBackoffMillis()
                < reliabilityProperties.getMysqlRetryInitialBackoffMillis()) {
            throw new IllegalStateException("MySQL retry backoff 설정이 올바르지 않습니다.");
        }
        if (reliabilityProperties.getAckDelayMillis() < 0) {
            throw new IllegalStateException("ACK 지연 설정은 0 이상이어야 합니다.");
        }
    }

    @PreDestroy
    public void close() {
        // 새 callback의 enqueue만 막고 이미 queue에 들어온 작업은 정상적으로 drain합니다.
        accepting.set(false);
        executors.forEach(ThreadPoolExecutor::shutdown);
        try {
            for (ThreadPoolExecutor executor : executors) {
                if (!executor.awaitTermination(properties.getShutdownAwaitSeconds(), TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            }
        } catch (InterruptedException exception) {
            executors.forEach(ThreadPoolExecutor::shutdownNow);
            Thread.currentThread().interrupt();
        }
    }
}
