package com.airs.backend.sensor.service;

import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.sensor.config.MqttProperties;
import com.airs.backend.sensor.entity.TelemetryOutbox;
import com.airs.backend.sensor.entity.TelemetryOutboxStatus;
import com.airs.backend.sensor.repository.TelemetryIngestionStateRepository;
import com.airs.backend.sensor.repository.TelemetryOutboxRepository;
import com.airs.backend.sensor.influx.InfluxDht22Writer;
import com.airs.backend.sensor.mqtt.MqttDht22Subscriber;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;

import static org.awaitility.Awaitility.await;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

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
    @Autowired
    private TelemetryIngestionDispatcher telemetryIngestionDispatcher;
    @Autowired
    private TelemetryPayloadValidator telemetryPayloadValidator;
    @Autowired
    private MqttProperties mqttProperties;
    @Autowired
    private MeterRegistry meterRegistry;

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
        doNothing().when(snapshotUpdateService).updateLatestSnapshot(any(), any(), any(), any());
        Instant receivedAt = Instant.parse("2026-08-02T10:00:00Z");

        Dht22Payload event = payload(42, receivedAt.minusSeconds(1));
        assertThat(ingestionService.ingest("node_01", event, receivedAt))
                .isEqualTo(TelemetryDeliveryDecision.ACCEPTED_CURRENT);
        assertThat(ingestionService.ingest("node_01", event, receivedAt.plusSeconds(1)))
                .isEqualTo(TelemetryDeliveryDecision.DUPLICATE);

        assertThat(stateRepository.findById("node_01")).get()
                .extracting("lastSequenceNo")
                .isEqualTo(42L);
        assertThat(outboxRepository.findAll()).hasSize(1);
    }

    @Test
    void transaction_should_roll_back_sequence_occupancy_and_outbox_when_snapshot_fails() throws Exception {
        doThrow(new RuntimeException("forced snapshot failure"))
                .when(snapshotUpdateService).updateLatestSnapshot(any(), any(), any(), any());
        Instant receivedAt = Instant.parse("2026-08-02T10:00:00Z");

        assertThatThrownBy(() -> ingestionService.ingest("node_01", payload(42, receivedAt.minusSeconds(1)), receivedAt))
                .hasMessageContaining("forced snapshot failure");
        assertThat(stateRepository.findById("node_01")).isEmpty();
        assertThat(outboxRepository.findAll()).isEmpty();

        reset(snapshotUpdateService);
        doNothing().when(snapshotUpdateService).updateLatestSnapshot(any(), any(), any(), any());
        ingestionService.ingest("node_01", payload(42, receivedAt.minusSeconds(1)), receivedAt.plusSeconds(1));

        TelemetryOutbox outbox = outboxRepository.findAll().getFirst();
        String occupancyState = objectMapper.readTree(outbox.getPointPayloadJson())
                .path("occupancyState")
                .asText();
        // 첫 PIR 한 번은 UNKNOWN이어야 하며 실패한 시도의 JVM 상태가 남아 PRESENT가 되면 안 된다.
        assertThat(occupancyState).isEqualTo("UNKNOWN");
    }

    @Test
    void stale_claim_should_be_claimable_after_timeout() {
        doNothing().when(snapshotUpdateService).updateLatestSnapshot(any(), any(), any(), any());
        Instant receivedAt = Instant.parse("2026-08-02T10:00:00Z");
        ingestionService.ingest("node_01", payload(42, receivedAt.minusSeconds(1)), receivedAt);

        assertThat(outboxStateService.claimDue(receivedAt.plusSeconds(1))).hasSize(1);
        assertThat(outboxStateService.claimDue(receivedAt.plusSeconds(10))).isEmpty();
        assertThat(outboxStateService.claimDue(receivedAt.plusSeconds(32))).hasSize(1);
    }

    @Test
    void cleanup_should_delete_only_completed_rows_older_than_cutoff() {
        doNothing().when(snapshotUpdateService).updateLatestSnapshot(any(), any(), any(), any());
        Instant receivedAt = Instant.parse("2026-08-02T10:00:00Z");
        ingestionService.ingest("node_01", payload(42, receivedAt.minusSeconds(1)), receivedAt);

        var completedIds = outboxStateService.claimDue(receivedAt.plusSeconds(1));
        outboxStateService.completeAll(completedIds, receivedAt.plusSeconds(2));

        Dht22Payload pendingPayload = payload(1, receivedAt.plusSeconds(2));
        pendingPayload.setBootId("boot-b");
        ingestionService.ingest("node_02", pendingPayload, receivedAt.plusSeconds(3));

        assertThat(outboxStateService.cleanupCompleted(receivedAt.plusSeconds(3))).isEqualTo(1);
        assertThat(outboxRepository.findAll()).singleElement()
                .extracting(TelemetryOutbox::getStatus)
                .isEqualTo(TelemetryOutboxStatus.PENDING);
    }

    @Test
    void cleanup_should_not_delete_pending_retry_or_claimed_rows_and_should_keep_ingestion_state() {
        doNothing().when(snapshotUpdateService).updateLatestSnapshot(any(), any(), any(), any());
        Instant base = Instant.parse("2026-08-02T10:00:00Z");

        ingestionService.ingest("node_completed", payload(1, base), base);
        var completedIds = outboxStateService.claimDue(base.plusMillis(100));
        outboxStateService.completeAll(completedIds, base.plusMillis(200));

        ingestionService.ingest("node_retry_1", payload(1, base), base.plusMillis(300));
        ingestionService.ingest("node_retry_2", payload(1, base), base.plusMillis(400));
        var retryIds = outboxStateService.claimDue(base.plusMillis(500));
        outboxStateService.retryAll(retryIds, base.plusMillis(500), "forced retry");

        ingestionService.ingest("node_claimed", payload(1, base), base.plusMillis(600));
        var claimedIds = outboxStateService.claimDue(base.plusMillis(700));
        assertThat(claimedIds).hasSize(1);

        ingestionService.ingest("node_pending", payload(1, base), base.plusMillis(800));

        assertThat(outboxStateService.cleanupCompleted(base.plusSeconds(10))).isEqualTo(1);
        assertThat(outboxRepository.countByStatus(TelemetryOutboxStatus.COMPLETED)).isZero();
        assertThat(outboxRepository.countByStatus(TelemetryOutboxStatus.RETRY)).isEqualTo(2);
        assertThat(outboxRepository.countByStatus(TelemetryOutboxStatus.PENDING)).isEqualTo(2);
        assertThat(stateRepository.count()).isEqualTo(5);
    }

    @Test
    void event_time_sequence_should_store_42_44_and_late_43_without_rewinding_snapshot_state() throws Exception {
        Instant observed42 = Instant.parse("2026-08-12T14:00:00Z");
        Instant observed43 = Instant.parse("2026-08-12T14:00:05Z");
        Instant observed44 = Instant.parse("2026-08-12T14:00:10Z");
        doNothing().when(snapshotUpdateService).updateLatestSnapshot(any(), any(), any(), any());

        assertThat(ingestionService.ingest("node_01", payload(42, observed42), observed42.plusSeconds(1)))
                .isEqualTo(TelemetryDeliveryDecision.ACCEPTED_CURRENT);
        assertThat(ingestionService.ingest("node_01", payload(44, observed44), observed44.plusSeconds(1)))
                .isEqualTo(TelemetryDeliveryDecision.ACCEPTED_CURRENT);
        reset(snapshotUpdateService);

        Dht22Payload late43 = payload(43, observed43);
        late43.setCo2Ppm(820);
        assertThat(ingestionService.ingest("node_01", late43, observed43.plusSeconds(10)))
                .isEqualTo(TelemetryDeliveryDecision.ACCEPTED_LATE);
        assertThat(ingestionService.ingest("node_01", late43, observed43.plusSeconds(11)))
                .isEqualTo(TelemetryDeliveryDecision.DUPLICATE);

        var state = stateRepository.findById("node_01").orElseThrow();
        assertThat(state.getLastSequenceNo()).isEqualTo(44L);
        assertThat(state.getLastObservedAt()).isEqualTo(observed44);
        assertThat(outboxRepository.findAll()).hasSize(3);
        TelemetryOutbox lateOutbox = outboxRepository.findAll().stream()
                .filter(outbox -> Long.valueOf(43L).equals(outbox.getSequenceNo()))
                .findFirst()
                .orElseThrow();
        var latePoint = objectMapper.readTree(lateOutbox.getPointPayloadJson());
        assertThat(latePoint.path("deliveryDecision").asText()).isEqualTo("ACCEPTED_LATE");
        assertThat(latePoint.path("observedAt").asText()).isEqualTo("2026-08-12T14:00:05Z");
        assertThat(latePoint.path("occupancyState").isNull()).isTrue();
        verifyNoInteractions(snapshotUpdateService);
    }

    @Test
    void delayed_previous_boot_should_not_replace_new_boot_session() {
        Instant observedA = Instant.parse("2026-08-12T14:00:00Z");
        Instant observedB = Instant.parse("2026-08-12T14:00:10Z");
        doNothing().when(snapshotUpdateService).updateLatestSnapshot(any(), any(), any(), any());

        ingestionService.ingest("node_01", payload("boot-a", 42, observedA), observedA.plusSeconds(1));
        ingestionService.ingest("node_01", payload("boot-b", 1, observedB), observedB.plusSeconds(1));
        reset(snapshotUpdateService);

        assertThat(ingestionService.ingest(
                "node_01",
                payload("boot-a", 43, observedA.plusSeconds(5)),
                observedB.plusSeconds(5)
        )).isEqualTo(TelemetryDeliveryDecision.ACCEPTED_LATE);

        var state = stateRepository.findById("node_01").orElseThrow();
        assertThat(state.getActiveBootId()).isEqualTo("boot-b");
        assertThat(state.getLastSequenceNo()).isEqualTo(1L);
        assertThat(state.getLastObservedAt()).isEqualTo(observedB);
        verifyNoInteractions(snapshotUpdateService);
    }

    @Test
    void virtual_mqtt_json_should_flow_from_subscriber_through_dispatcher_to_mysql_transaction() {
        Instant observedAt = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MICROS);
        doNothing().when(snapshotUpdateService).updateLatestSnapshot(any(), any(), any(), any());
        MqttDht22Subscriber subscriber = new MqttDht22Subscriber(
                mqttProperties,
                objectMapper,
                telemetryIngestionDispatcher,
                telemetryPayloadValidator,
                meterRegistry
        );
        String json = """
                {
                  "node_id": "node_virtual",
                  "boot_id": "boot-test",
                  "sequence_no": 7,
                  "observed_at": "%s",
                  "temperature_c": 24.3,
                  "humidity_pct": 52.0,
                  "co2_ppm": 820,
                  "pir_detected": 0,
                  "mmwave_detected": 1,
                  "wifi_signal_dbm": -58
                }
                """.formatted(observedAt);
        MqttMessage message = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
        message.setId(101);
        message.setQos(1);

        ReflectionTestUtils.invokeMethod(
                subscriber,
                "handleMessage",
                "airs/node/node_virtual/telemetry",
                message
        );

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(stateRepository.findById("node_virtual")).get()
                    .extracting("activeBootId", "lastSequenceNo", "lastObservedAt")
                    .containsExactly("boot-test", 7L, observedAt);
            assertThat(outboxRepository.findAll()).singleElement().satisfies(outbox -> {
                assertThat(outbox.getEventKey()).isEqualTo("node_virtual|boot-test|7");
                assertThat(outbox.getSchemaVersion()).isEqualTo(2);
            });
        });
    }

    private Dht22Payload payload(long sequenceNo, Instant observedAt) {
        return payload("boot-a", sequenceNo, observedAt);
    }

    private Dht22Payload payload(String bootId, long sequenceNo, Instant observedAt) {
        Dht22Payload payload = new Dht22Payload(24.3, 52.0, 812, observedAt);
        payload.setBootId(bootId);
        payload.setSequenceNo(sequenceNo);
        payload.setPirDetected(1);
        payload.setMmwaveDetected(0);
        return payload;
    }
}
