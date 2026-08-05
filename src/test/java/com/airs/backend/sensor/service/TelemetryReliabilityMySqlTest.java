package com.airs.backend.sensor.service;

import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.sensor.entity.TelemetryOutbox;
import com.airs.backend.sensor.entity.TelemetryOutboxStatus;
import com.airs.backend.sensor.repository.TelemetryIngestionStateRepository;
import com.airs.backend.sensor.repository.TelemetryOutboxRepository;
import com.airs.backend.sensor.influx.InfluxDht22Writer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(properties = {
        "mqtt.enabled=false",
        "ai.evaluation.scheduler.enabled=false",
        "sensor.telemetry.reliability.publisher-enabled=false",
        "sensor.telemetry.reliability.cleanup-enabled=false"
})
class TelemetryReliabilityMySqlTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("airs_telemetry_reliability_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
    }

    @Autowired
    private Dht22IngestionService ingestionService;
    @Autowired
    private TelemetryIngestionStateRepository stateRepository;
    @Autowired
    private TelemetryOutboxRepository outboxRepository;
    @Autowired
    private TelemetryOutboxStateService outboxStateService;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private Dht22SnapshotUpdateService snapshotUpdateService;
    @MockitoBean
    private InfluxDht22Writer influxDht22Writer;

    @AfterEach
    void cleanUp() {
        outboxRepository.deleteAllInBatch();
        stateRepository.deleteAllInBatch();
        reset(snapshotUpdateService, influxDht22Writer);
    }

    @Test
    void transaction_should_commit_sequence_and_outbox_and_reject_redelivery() {
        doNothing().when(snapshotUpdateService).updateLatestSnapshot(any(), any(), any());
        Instant receivedAt = Instant.parse("2026-08-02T10:00:00Z");

        assertThat(ingestionService.ingest("node_01", payload(42), receivedAt))
                .isEqualTo(TelemetryDeliveryDecision.ACCEPTED);
        assertThat(ingestionService.ingest("node_01", payload(42), receivedAt.plusSeconds(1)))
                .isEqualTo(TelemetryDeliveryDecision.DUPLICATE);

        assertThat(stateRepository.findById("node_01")).get()
                .extracting("lastSequenceNo")
                .isEqualTo(42L);
        assertThat(outboxRepository.findAll()).hasSize(1);
    }

    @Test
    void transaction_should_roll_back_sequence_occupancy_and_outbox_when_snapshot_fails() throws Exception {
        doThrow(new RuntimeException("forced snapshot failure"))
                .when(snapshotUpdateService).updateLatestSnapshot(any(), any(), any());
        Instant receivedAt = Instant.parse("2026-08-02T10:00:00Z");

        assertThatThrownBy(() -> ingestionService.ingest("node_01", payload(42), receivedAt))
                .hasMessageContaining("forced snapshot failure");
        assertThat(stateRepository.findById("node_01")).isEmpty();
        assertThat(outboxRepository.findAll()).isEmpty();

        reset(snapshotUpdateService);
        doNothing().when(snapshotUpdateService).updateLatestSnapshot(any(), any(), any());
        ingestionService.ingest("node_01", payload(42), receivedAt.plusSeconds(1));

        TelemetryOutbox outbox = outboxRepository.findAll().getFirst();
        String occupancyState = objectMapper.readTree(outbox.getPointPayloadJson())
                .path("occupancyState")
                .asText();
        // 첫 PIR 한 번은 UNKNOWN이어야 하며 실패한 시도의 JVM 상태가 남아 PRESENT가 되면 안 된다.
        assertThat(occupancyState).isEqualTo("UNKNOWN");
    }

    @Test
    void stale_claim_should_be_claimable_after_timeout() {
        doNothing().when(snapshotUpdateService).updateLatestSnapshot(any(), any(), any());
        Instant receivedAt = Instant.parse("2026-08-02T10:00:00Z");
        ingestionService.ingest("node_01", payload(42), receivedAt);

        assertThat(outboxStateService.claimDue(receivedAt.plusSeconds(1))).hasSize(1);
        assertThat(outboxStateService.claimDue(receivedAt.plusSeconds(10))).isEmpty();
        assertThat(outboxStateService.claimDue(receivedAt.plusSeconds(32))).hasSize(1);
    }

    @Test
    void cleanup_should_delete_only_completed_rows_older_than_cutoff() {
        doNothing().when(snapshotUpdateService).updateLatestSnapshot(any(), any(), any());
        Instant receivedAt = Instant.parse("2026-08-02T10:00:00Z");
        ingestionService.ingest("node_01", payload(42), receivedAt);

        var completedIds = outboxStateService.claimDue(receivedAt.plusSeconds(1));
        outboxStateService.completeAll(completedIds, receivedAt.plusSeconds(2));

        Dht22Payload pendingPayload = payload(1);
        pendingPayload.setBootId("boot-b");
        ingestionService.ingest("node_02", pendingPayload, receivedAt.plusSeconds(3));

        assertThat(outboxStateService.cleanupCompleted(receivedAt.plusSeconds(3))).isEqualTo(1);
        assertThat(outboxRepository.findAll()).singleElement()
                .extracting(TelemetryOutbox::getStatus)
                .isEqualTo(TelemetryOutboxStatus.PENDING);
    }

    @Test
    void cleanup_should_not_delete_pending_retry_or_claimed_rows_and_should_keep_ingestion_state() {
        doNothing().when(snapshotUpdateService).updateLatestSnapshot(any(), any(), any());
        Instant base = Instant.parse("2026-08-02T10:00:00Z");

        ingestionService.ingest("node_completed", payload(1), base);
        var completedIds = outboxStateService.claimDue(base.plusMillis(100));
        outboxStateService.completeAll(completedIds, base.plusMillis(200));

        ingestionService.ingest("node_retry_1", payload(1), base.plusMillis(300));
        ingestionService.ingest("node_retry_2", payload(1), base.plusMillis(400));
        var retryIds = outboxStateService.claimDue(base.plusMillis(500));
        outboxStateService.retryAll(retryIds, base.plusMillis(500), "forced retry");

        ingestionService.ingest("node_claimed", payload(1), base.plusMillis(600));
        var claimedIds = outboxStateService.claimDue(base.plusMillis(700));
        assertThat(claimedIds).hasSize(1);

        ingestionService.ingest("node_pending", payload(1), base.plusMillis(800));

        assertThat(outboxStateService.cleanupCompleted(base.plusSeconds(10))).isEqualTo(1);
        assertThat(outboxRepository.countByStatus(TelemetryOutboxStatus.COMPLETED)).isZero();
        assertThat(outboxRepository.countByStatus(TelemetryOutboxStatus.RETRY)).isEqualTo(2);
        assertThat(outboxRepository.countByStatus(TelemetryOutboxStatus.PENDING)).isEqualTo(2);
        assertThat(stateRepository.count()).isEqualTo(5);
    }

    private Dht22Payload payload(long sequenceNo) {
        Dht22Payload payload = new Dht22Payload(24.3, 52.0, 812, null);
        payload.setBootId("boot-a");
        payload.setSequenceNo(sequenceNo);
        payload.setPirDetected(1);
        payload.setMmwaveDetected(0);
        return payload;
    }
}
