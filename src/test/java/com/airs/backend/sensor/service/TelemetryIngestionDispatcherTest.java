package com.airs.backend.sensor.service;

import com.airs.backend.sensor.config.TelemetryIngestionProperties;
import com.airs.backend.sensor.config.TelemetryReliabilityProperties;
import com.airs.backend.sensor.dto.Dht22Payload;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

class TelemetryIngestionDispatcherTest {

    private TelemetryIngestionDispatcher dispatcher;

    @AfterEach
    void tearDown() {
        if (dispatcher != null) {
            dispatcher.close();
        }
    }

    @Test
    void dispatch_should_preserve_order_and_ack_after_ingestion_for_same_node() throws Exception {
        CountDownLatch completed = new CountDownLatch(2);
        List<Long> sequenceNumbers = new CopyOnWriteArrayList<>();
        List<Long> acknowledged = new CopyOnWriteArrayList<>();
        Dht22IngestionService ingestionService = Mockito.mock(Dht22IngestionService.class);
        doAnswer(invocation -> {
            sequenceNumbers.add(invocation.getArgument(1, Dht22Payload.class).getSequenceNo());
            return TelemetryDeliveryDecision.ACCEPTED_CURRENT;
        }).when(ingestionService).ingest(eq("node_01"), any(Dht22Payload.class), any(Instant.class));

        dispatcher = dispatcher(ingestionService);
        dispatcher.dispatch(command(41, acknowledged, completed));
        dispatcher.dispatch(command(42, acknowledged, completed));

        assertTrue(completed.await(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS));
        assertEquals(List.of(41L, 42L), sequenceNumbers);
        assertEquals(List.of(41L, 42L), acknowledged);
    }

    @Test
    void internal_failure_should_retry_same_event_before_acknowledging_it() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        List<Long> acknowledged = new CopyOnWriteArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        Dht22IngestionService ingestionService = Mockito.mock(Dht22IngestionService.class);
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("forced internal failure");
            }
            return TelemetryDeliveryDecision.ACCEPTED_CURRENT;
        }).when(ingestionService).ingest(eq("node_01"), any(Dht22Payload.class), any(Instant.class));

        dispatcher = dispatcher(ingestionService);
        dispatcher.dispatch(command(42, acknowledged, completed));

        assertTrue(completed.await(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS));
        assertEquals(2, attempts.get());
        assertEquals(List.of(42L), acknowledged);
    }

    @Test
    void close_should_drain_an_already_queued_event_before_shutdown_timeout() {
        CountDownLatch completed = new CountDownLatch(1);
        List<Long> acknowledged = new CopyOnWriteArrayList<>();
        Dht22IngestionService ingestionService = Mockito.mock(Dht22IngestionService.class);
        doAnswer(invocation -> TelemetryDeliveryDecision.ACCEPTED_CURRENT)
                .when(ingestionService).ingest(eq("node_01"), any(Dht22Payload.class), any(Instant.class));

        dispatcher = dispatcher(ingestionService);
        dispatcher.dispatch(command(42, acknowledged, completed));
        dispatcher.close();
        dispatcher = null;

        assertEquals(List.of(42L), acknowledged);
    }

    private TelemetryIngestionDispatcher dispatcher(Dht22IngestionService ingestionService) {
        TelemetryIngestionProperties ingestion = new TelemetryIngestionProperties();
        ingestion.setWorkerCount(2);
        ingestion.setQueueCapacity(10);
        ingestion.setShutdownAwaitSeconds(1);
        TelemetryReliabilityProperties reliability = new TelemetryReliabilityProperties();
        reliability.setMysqlRetryInitialBackoffMillis(10);
        reliability.setMysqlRetryMaximumBackoffMillis(20);
        TelemetryIngestionDispatcher created = new TelemetryIngestionDispatcher(
                ingestion,
                reliability,
                ingestionService,
                new SimpleMeterRegistry()
        );
        created.initialize();
        return created;
    }

    private TelemetryIngestionCommand command(
            long sequenceNo,
            List<Long> acknowledged,
            CountDownLatch completed
    ) {
        Dht22Payload payload = new Dht22Payload();
        payload.setTemperature(24.0);
        payload.setHumidity(50.0);
        payload.setObservedAt(Instant.parse("2026-07-28T00:00:00Z"));
        payload.setBootId("boot-a");
        payload.setSequenceNo(sequenceNo);
        return new TelemetryIngestionCommand(
                "node_01",
                payload,
                Instant.parse("2026-07-28T00:00:01Z"),
                (int) sequenceNo,
                1,
                () -> {
                    acknowledged.add(sequenceNo);
                    completed.countDown();
                }
        );
    }
}
