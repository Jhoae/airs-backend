package com.airs.backend.sensor.service;

import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.sensor.dto.TelemetryPointPayload;
import com.airs.backend.sensor.entity.TelemetryIngestionState;
import com.airs.backend.sensor.entity.TelemetryOutbox;
import com.airs.backend.sensor.repository.TelemetryIngestionStateRepository;
import com.airs.backend.sensor.repository.TelemetryOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class Dht22IngestionService {

    private static final Logger log = LoggerFactory.getLogger(Dht22IngestionService.class);

    private final TelemetryPayloadValidator telemetryPayloadValidator;
    private final TelemetryIngestionStateRepository telemetryIngestionStateRepository;
    private final TelemetryOutboxRepository telemetryOutboxRepository;
    private final OccupancyFusionService occupancyFusionService;
    private final Dht22SnapshotUpdateService dht22SnapshotUpdateService;
    private final ObjectMapper objectMapper;

    // sequence·occupancy·snapshot·Influx outbox를 하나의 MySQL transaction으로 확정한다.
    @Transactional
    public TelemetryDeliveryDecision ingest(String nodeId, Dht22Payload payload, Instant receivedAt) {
        telemetryPayloadValidator.validateAndStamp(nodeId, payload, receivedAt);

        // 빈 row를 FOR UPDATE로 조회해 gap lock을 잡지 않고, 같은 transaction 안에서 먼저 idempotent하게 준비합니다.
        telemetryIngestionStateRepository.insertIfMissing(nodeId, receivedAt);

        TelemetryIngestionState state = telemetryIngestionStateRepository.findByNodeIdForUpdate(nodeId)
                .orElseThrow(() -> new IllegalStateException("초기화한 telemetry ingestion state를 찾을 수 없습니다. nodeId=" + nodeId));

        TelemetryDeliveryDecision decision = decideSequence(state, payload);
        if (!decision.shouldIngest()) {
            log.info("중복 또는 순서 역전 telemetry를 저장 전에 건너뜁니다. nodeId={}, decision={}, bootId={}, sequenceNo={}",
                    nodeId, decision, payload.getBootId(), payload.getSequenceNo());
            return decision;
        }

        OccupancyFusionTransition occupancyTransition = occupancyFusionService.resolve(
                payload,
                state.occupancyMemory()
        );

        dht22SnapshotUpdateService.updateLatestSnapshot(
                nodeId,
                payload,
                occupancyTransition.result()
        );

        TelemetryPointPayload pointPayload = TelemetryPointPayload.from(
                nodeId,
                payload,
                occupancyTransition.result()
        );
        telemetryOutboxRepository.save(new TelemetryOutbox(
                eventKey(nodeId, payload),
                nodeId,
                payload.getBootId(),
                payload.getSequenceNo(),
                receivedAt,
                serialize(pointPayload),
                TelemetryPointPayload.SCHEMA_VERSION
        ));

        state.acceptSequence(payload.getBootId(), payload.getSequenceNo(), receivedAt);
        state.applyOccupancy(occupancyTransition);
        telemetryIngestionStateRepository.save(state);
        return decision;
    }

    private TelemetryDeliveryDecision decideSequence(
            TelemetryIngestionState state,
            Dht22Payload payload
    ) {
        if (!telemetryPayloadValidator.hasReliableIdentity(payload)) {
            return TelemetryDeliveryDecision.LEGACY_BYPASS;
        }
        if (state.getActiveBootId() == null || state.getLastSequenceNo() == null) {
            return TelemetryDeliveryDecision.ACCEPTED;
        }
        if (!state.getActiveBootId().equals(payload.getBootId())) {
            log.info("새 telemetry boot session을 수신했습니다. nodeId={}, previousBootId={}, bootId={}",
                    state.getNodeId(), state.getActiveBootId(), payload.getBootId());
            return TelemetryDeliveryDecision.ACCEPTED;
        }

        int comparison = payload.getSequenceNo().compareTo(state.getLastSequenceNo());
        if (comparison == 0) {
            return TelemetryDeliveryDecision.DUPLICATE;
        }
        if (comparison < 0) {
            return TelemetryDeliveryDecision.OUT_OF_ORDER;
        }
        if (payload.getSequenceNo() > state.getLastSequenceNo() + 1) {
            log.warn("telemetry sequence gap을 확인했습니다. nodeId={}, bootId={}, previous={}, incoming={}",
                    state.getNodeId(), payload.getBootId(), state.getLastSequenceNo(), payload.getSequenceNo());
        }
        return TelemetryDeliveryDecision.ACCEPTED;
    }

    private String eventKey(String nodeId, Dht22Payload payload) {
        if (telemetryPayloadValidator.hasReliableIdentity(payload)) {
            return nodeId + "|" + payload.getBootId() + "|" + payload.getSequenceNo();
        }
        return "legacy|" + nodeId + "|" + UUID.randomUUID();
    }

    private String serialize(TelemetryPointPayload pointPayload) {
        try {
            return objectMapper.writeValueAsString(pointPayload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("InfluxDB outbox payload를 직렬화할 수 없습니다.", exception);
        }
    }
}
