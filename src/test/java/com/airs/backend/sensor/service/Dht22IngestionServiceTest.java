package com.airs.backend.sensor.service;

import com.airs.backend.sensor.config.TelemetryReliabilityProperties;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.sensor.dto.TelemetryPointPayload;
import com.airs.backend.sensor.entity.TelemetryIngestionState;
import com.airs.backend.sensor.entity.TelemetryOutbox;
import com.airs.backend.sensor.repository.TelemetryIngestionStateRepository;
import com.airs.backend.sensor.repository.TelemetryOutboxRepository;
import com.airs.backend.status.entity.OccupancyStatus;
import com.airs.backend.status.entity.TelemetryOccupancyState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Dht22IngestionServiceTest {

    @Mock
    private TelemetryIngestionStateRepository stateRepository;
    @Mock
    private TelemetryOutboxRepository outboxRepository;
    @Mock
    private OccupancyFusionService occupancyFusionService;
    @Mock
    private Dht22SnapshotUpdateService snapshotUpdateService;

    private Dht22IngestionService service;
    private ObjectMapper objectMapper;
    private final Instant receivedAt = Instant.parse("2026-08-12T14:00:15Z");

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new Dht22IngestionService(
                new TelemetryPayloadValidator(new TelemetryReliabilityProperties()),
                stateRepository,
                outboxRepository,
                occupancyFusionService,
                snapshotUpdateService,
                objectMapper
        );
    }

    @Test
    void current_should_update_snapshot_state_and_raw_outbox() throws Exception {
        Instant observedAt = Instant.parse("2026-08-12T14:00:10Z");
        Dht22Payload payload = payload("boot-a", 44, observedAt);
        TelemetryIngestionState state = new TelemetryIngestionState("node_01");
        when(stateRepository.findByNodeIdForUpdate("node_01")).thenReturn(Optional.of(state));
        when(occupancyFusionService.resolve(eq(payload), any(OccupancyFusionMemory.class)))
                .thenReturn(transition());

        TelemetryDeliveryDecision decision = service.ingest("node_01", payload, receivedAt);

        assertEquals(TelemetryDeliveryDecision.ACCEPTED_CURRENT, decision);
        verify(stateRepository).insertIfMissing("node_01", receivedAt);
        verify(snapshotUpdateService).updateLatestSnapshot("node_01", payload, transition().result(), receivedAt);
        ArgumentCaptor<TelemetryOutbox> outboxCaptor = ArgumentCaptor.forClass(TelemetryOutbox.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        TelemetryOutbox outbox = outboxCaptor.getValue();
        TelemetryPointPayload point = objectMapper.readValue(outbox.getPointPayloadJson(), TelemetryPointPayload.class);
        assertThat(outbox.getEventKey()).isEqualTo("node_01|boot-a|44");
        assertThat(point.observedAt()).isEqualTo(observedAt);
        assertThat(point.receivedAt()).isEqualTo(receivedAt);
        assertThat(point.ingestDelayMillis()).isEqualTo(5_000L);
        assertThat(point.deliveryDecision()).isEqualTo("ACCEPTED_CURRENT");
        assertThat(state.getLastSequenceNo()).isEqualTo(44L);
        assertThat(state.getLastObservedAt()).isEqualTo(observedAt);
    }

    @Test
    void same_current_event_should_be_duplicate_before_derived_processing() {
        Instant observedAt = Instant.parse("2026-08-12T14:00:10Z");
        Dht22Payload payload = payload("boot-a", 44, observedAt);
        when(stateRepository.findByNodeIdForUpdate("node_01"))
                .thenReturn(Optional.of(state("boot-a", 44, observedAt)));

        TelemetryDeliveryDecision decision = service.ingest("node_01", payload, receivedAt);

        assertEquals(TelemetryDeliveryDecision.DUPLICATE, decision);
        verify(outboxRepository, never()).save(any());
        verifyNoInteractions(occupancyFusionService, snapshotUpdateService);
    }

    @Test
    void late_unique_should_create_raw_only_outbox_without_rewinding_current_state() throws Exception {
        Instant latestObservedAt = Instant.parse("2026-08-12T14:00:10Z");
        Instant lateObservedAt = Instant.parse("2026-08-12T14:00:05Z");
        TelemetryIngestionState state = state("boot-a", 44, latestObservedAt);
        Dht22Payload late = payload("boot-a", 43, lateObservedAt);
        when(stateRepository.findByNodeIdForUpdate("node_01")).thenReturn(Optional.of(state));

        TelemetryDeliveryDecision decision = service.ingest("node_01", late, receivedAt);

        assertEquals(TelemetryDeliveryDecision.ACCEPTED_LATE, decision);
        ArgumentCaptor<TelemetryOutbox> outboxCaptor = ArgumentCaptor.forClass(TelemetryOutbox.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        TelemetryPointPayload point = objectMapper.readValue(
                outboxCaptor.getValue().getPointPayloadJson(),
                TelemetryPointPayload.class
        );
        assertThat(point.deliveryDecision()).isEqualTo("ACCEPTED_LATE");
        assertThat(point.observedAt()).isEqualTo(lateObservedAt);
        assertThat(point.occupancyState()).isNull();
        assertThat(point.occupancyPresent()).isNull();
        assertThat(state.getLastSequenceNo()).isEqualTo(44L);
        assertThat(state.getLastObservedAt()).isEqualTo(latestObservedAt);
        verifyNoInteractions(occupancyFusionService, snapshotUpdateService);
    }

    @Test
    void same_late_event_should_be_duplicate_when_bounded_outbox_key_exists() {
        Instant latestObservedAt = Instant.parse("2026-08-12T14:00:10Z");
        Dht22Payload late = payload("boot-a", 43, Instant.parse("2026-08-12T14:00:05Z"));
        when(stateRepository.findByNodeIdForUpdate("node_01"))
                .thenReturn(Optional.of(state("boot-a", 44, latestObservedAt)));
        when(outboxRepository.existsByEventKey("node_01|boot-a|43")).thenReturn(true);

        assertEquals(TelemetryDeliveryDecision.DUPLICATE, service.ingest("node_01", late, receivedAt));
        verify(outboxRepository, never()).save(any());
        verifyNoInteractions(occupancyFusionService, snapshotUpdateService);
    }

    @Test
    void newer_observed_time_from_new_boot_should_be_current() {
        TelemetryIngestionState state = state(
                "boot-a",
                44,
                Instant.parse("2026-08-12T14:00:10Z")
        );
        Dht22Payload payload = payload("boot-b", 1, Instant.parse("2026-08-12T14:00:20Z"));
        when(stateRepository.findByNodeIdForUpdate("node_01")).thenReturn(Optional.of(state));
        when(occupancyFusionService.resolve(eq(payload), any(OccupancyFusionMemory.class)))
                .thenReturn(transition());

        assertEquals(TelemetryDeliveryDecision.ACCEPTED_CURRENT, service.ingest("node_01", payload, receivedAt.plusSeconds(10)));
        assertThat(state.getActiveBootId()).isEqualTo("boot-b");
        assertThat(state.getLastSequenceNo()).isEqualTo(1L);
    }

    @Test
    void delayed_previous_boot_should_be_late_and_not_replace_active_boot() {
        TelemetryIngestionState state = state(
                "boot-b",
                1,
                Instant.parse("2026-08-12T14:00:20Z")
        );
        Dht22Payload delayedBootA = payload("boot-a", 45, Instant.parse("2026-08-12T14:00:15Z"));
        when(stateRepository.findByNodeIdForUpdate("node_01")).thenReturn(Optional.of(state));

        assertEquals(
                TelemetryDeliveryDecision.ACCEPTED_LATE,
                service.ingest("node_01", delayedBootA, receivedAt.plusSeconds(10))
        );
        assertThat(state.getActiveBootId()).isEqualTo("boot-b");
        assertThat(state.getLastSequenceNo()).isEqualTo(1L);
        verifyNoInteractions(occupancyFusionService, snapshotUpdateService);
    }

    @Test
    void sequence_gap_with_newer_event_time_should_still_be_current() {
        Instant previousObservedAt = Instant.parse("2026-08-12T14:00:00Z");
        TelemetryIngestionState state = state("boot-a", 42, previousObservedAt);
        Dht22Payload payload = payload("boot-a", 44, Instant.parse("2026-08-12T14:00:10Z"));
        when(stateRepository.findByNodeIdForUpdate("node_01")).thenReturn(Optional.of(state));
        when(occupancyFusionService.resolve(eq(payload), any(OccupancyFusionMemory.class)))
                .thenReturn(transition());

        assertEquals(TelemetryDeliveryDecision.ACCEPTED_CURRENT, service.ingest("node_01", payload, receivedAt));
        assertThat(state.getLastSequenceNo()).isEqualTo(44L);
    }

    @Test
    void invalid_sensor_value_should_fail_before_database_work() {
        Dht22Payload payload = payload("boot-a", 1, receivedAt.minusSeconds(1));
        payload.setHumidity(101.0);

        assertThrows(IllegalArgumentException.class, () -> service.ingest("node_01", payload, receivedAt));
        verifyNoInteractions(stateRepository, outboxRepository, occupancyFusionService, snapshotUpdateService);
    }

    private TelemetryIngestionState state(String bootId, long sequenceNo, Instant observedAt) {
        TelemetryIngestionState state = new TelemetryIngestionState("node_01");
        state.acceptCurrent(bootId, sequenceNo, observedAt, receivedAt.minusSeconds(1));
        return state;
    }

    private Dht22Payload payload(String bootId, long sequenceNo, Instant observedAt) {
        Dht22Payload payload = new Dht22Payload(24.3, 52.0, 812, observedAt);
        payload.setBootId(bootId);
        payload.setSequenceNo(sequenceNo);
        payload.setPirDetected(0);
        payload.setMmwaveDetected(1);
        return payload;
    }

    private OccupancyFusionTransition transition() {
        return new OccupancyFusionTransition(
                new OccupancyFusionResult(
                        TelemetryOccupancyState.PRESENT,
                        true,
                        OccupancyStatus.OCCUPIED,
                        1,
                        0.0,
                        true
                ),
                new OccupancyFusionMemory(false, receivedAt, null)
        );
    }
}
