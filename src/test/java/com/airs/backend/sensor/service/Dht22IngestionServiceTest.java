package com.airs.backend.sensor.service;

import com.airs.backend.sensor.dto.Dht22Payload;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private final Instant receivedAt = Instant.parse("2026-08-02T10:00:00Z");

    @BeforeEach
    void setUp() {
        service = new Dht22IngestionService(
                new TelemetryPayloadValidator(),
                stateRepository,
                outboxRepository,
                occupancyFusionService,
                snapshotUpdateService,
                new ObjectMapper().findAndRegisterModules()
        );
    }

    @Test
    void ingest_should_save_snapshot_state_and_outbox_in_one_call() {
        Dht22Payload payload = payload("boot-a", 42);
        when(stateRepository.findByNodeIdForUpdate("node_01"))
                .thenReturn(Optional.of(new TelemetryIngestionState("node_01")));
        when(occupancyFusionService.resolve(eq(payload), any(OccupancyFusionMemory.class)))
                .thenReturn(transition());

        TelemetryDeliveryDecision decision = service.ingest("node_01", payload, receivedAt);

        assertEquals(TelemetryDeliveryDecision.ACCEPTED, decision);
        verify(stateRepository).insertIfMissing("node_01", receivedAt);
        verify(snapshotUpdateService).updateLatestSnapshot("node_01", payload, transition().result());
        ArgumentCaptor<TelemetryOutbox> outboxCaptor = ArgumentCaptor.forClass(TelemetryOutbox.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertEquals("node_01|boot-a|42", outboxCaptor.getValue().getEventKey());
        assertEquals(receivedAt, outboxCaptor.getValue().getReceivedAt());
        verify(stateRepository).save(any(TelemetryIngestionState.class));
    }

    @Test
    void ingest_should_reject_duplicate_before_occupancy_and_snapshot() {
        Dht22Payload payload = payload("boot-a", 42);
        TelemetryIngestionState state = state("boot-a", 42);
        when(stateRepository.findByNodeIdForUpdate("node_01")).thenReturn(Optional.of(state));

        TelemetryDeliveryDecision decision = service.ingest("node_01", payload, receivedAt);

        assertEquals(TelemetryDeliveryDecision.DUPLICATE, decision);
        verifyNoInteractions(occupancyFusionService, snapshotUpdateService, outboxRepository);
    }

    @Test
    void ingest_should_reject_out_of_order_before_snapshot() {
        Dht22Payload payload = payload("boot-a", 41);
        when(stateRepository.findByNodeIdForUpdate("node_01"))
                .thenReturn(Optional.of(state("boot-a", 42)));

        assertEquals(
                TelemetryDeliveryDecision.OUT_OF_ORDER,
                service.ingest("node_01", payload, receivedAt)
        );
        verifyNoInteractions(occupancyFusionService, snapshotUpdateService, outboxRepository);
    }

    @Test
    void ingest_should_accept_new_boot_session() {
        Dht22Payload payload = payload("boot-b", 1);
        when(stateRepository.findByNodeIdForUpdate("node_01"))
                .thenReturn(Optional.of(state("boot-a", 42)));
        when(occupancyFusionService.resolve(eq(payload), any(OccupancyFusionMemory.class)))
                .thenReturn(transition());

        assertEquals(TelemetryDeliveryDecision.ACCEPTED, service.ingest("node_01", payload, receivedAt));
        verify(outboxRepository).save(any(TelemetryOutbox.class));
    }

    @Test
    void ingest_should_reject_invalid_sensor_value_before_database_work() {
        Dht22Payload payload = payload("boot-a", 1);
        payload.setHumidity(101.0);

        assertThrows(IllegalArgumentException.class, () -> service.ingest("node_01", payload, receivedAt));
        verifyNoInteractions(stateRepository, outboxRepository, occupancyFusionService, snapshotUpdateService);
    }

    @Test
    void ingest_should_reject_non_binary_presence_sensor_before_database_work() {
        Dht22Payload payload = payload("boot-a", 1);
        payload.setPirDetected(2);

        assertThrows(IllegalArgumentException.class, () -> service.ingest("node_01", payload, receivedAt));
        verifyNoInteractions(stateRepository, outboxRepository, occupancyFusionService, snapshotUpdateService);
    }

    private TelemetryIngestionState state(String bootId, long sequenceNo) {
        TelemetryIngestionState state = new TelemetryIngestionState("node_01");
        state.acceptSequence(bootId, sequenceNo, receivedAt.minusSeconds(5));
        return state;
    }

    private Dht22Payload payload(String bootId, long sequenceNo) {
        Dht22Payload payload = new Dht22Payload(24.3, 52.0, 812, receivedAt);
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
