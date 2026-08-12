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
        telemetryPayloadValidator.validate(nodeId, payload, receivedAt);

        // 빈 row를 FOR UPDATE로 조회해 gap lock을 잡지 않고, 같은 transaction 안에서 먼저 idempotent하게 준비합니다.
        telemetryIngestionStateRepository.insertIfMissing(nodeId, receivedAt);

        TelemetryIngestionState state = telemetryIngestionStateRepository.findByNodeIdForUpdate(nodeId)
                .orElseThrow(() -> new IllegalStateException("초기화한 telemetry ingestion state를 찾을 수 없습니다. nodeId=" + nodeId));

        String eventKey = eventKey(nodeId, payload);
        TelemetryDeliveryDecision decision = decideDelivery(state, payload, eventKey);
        state.markReceived(receivedAt);

        if (decision == TelemetryDeliveryDecision.DUPLICATE) {
            telemetryIngestionStateRepository.save(state);
            log.info("이미 처리한 MQTT telemetry 재전달을 건너뜁니다. nodeId={}, bootId={}, sequenceNo={}",
                    nodeId, payload.getBootId(), payload.getSequenceNo());
            return decision;
        }

        if (decision == TelemetryDeliveryDecision.ACCEPTED_LATE) {
            saveOutbox(
                    eventKey,
                    nodeId,
                    payload,
                    receivedAt,
                    TelemetryPointPayload.fromLate(nodeId, payload, receivedAt)
            );
            telemetryIngestionStateRepository.save(state);
            log.info("늦게 도착한 고유 telemetry를 raw outbox에만 저장합니다. nodeId={}, bootId={}, sequenceNo={}, observedAt={}",
                    nodeId, payload.getBootId(), payload.getSequenceNo(), payload.getObservedAt());
            return decision;
        }

        OccupancyFusionTransition occupancyTransition = occupancyFusionService.resolve(
                payload,
                state.occupancyMemory()
        );

        dht22SnapshotUpdateService.updateLatestSnapshot(
                nodeId,
                payload,
                occupancyTransition.result(),
                receivedAt
        );

        TelemetryPointPayload pointPayload = TelemetryPointPayload.fromCurrent(
                nodeId,
                payload,
                receivedAt,
                occupancyTransition.result()
        );
        saveOutbox(eventKey, nodeId, payload, receivedAt, pointPayload);

        state.acceptCurrent(payload.getBootId(), payload.getSequenceNo(), payload.getObservedAt(), receivedAt);
        state.applyOccupancy(occupancyTransition);
        telemetryIngestionStateRepository.save(state);
        return decision;
    }

    private TelemetryDeliveryDecision decideDelivery(
            TelemetryIngestionState state,
            Dht22Payload payload,
            String eventKey
    ) {
        if (telemetryOutboxRepository.existsByEventKey(eventKey)) {
            return TelemetryDeliveryDecision.DUPLICATE;
        }
        if (state.getActiveBootId() == null || state.getLastSequenceNo() == null) {
            return TelemetryDeliveryDecision.ACCEPTED_CURRENT;
        }

        if (!state.getActiveBootId().equals(payload.getBootId())) {
            if (state.getLastObservedAt() == null || payload.getObservedAt().isAfter(state.getLastObservedAt())) {
                log.info("새 telemetry boot session을 수신했습니다. nodeId={}, previousBootId={}, bootId={}",
                        state.getNodeId(), state.getActiveBootId(), payload.getBootId());
                return TelemetryDeliveryDecision.ACCEPTED_CURRENT;
            }
            return TelemetryDeliveryDecision.ACCEPTED_LATE;
        }

        int comparison = payload.getSequenceNo().compareTo(state.getLastSequenceNo());
        if (comparison == 0) {
            return TelemetryDeliveryDecision.DUPLICATE;
        }
        if (comparison < 0) {
            return TelemetryDeliveryDecision.ACCEPTED_LATE;
        }
        if (state.getLastObservedAt() != null && !payload.getObservedAt().isAfter(state.getLastObservedAt())) {
            return TelemetryDeliveryDecision.ACCEPTED_LATE;
        }
        if (payload.getSequenceNo() > state.getLastSequenceNo() + 1) {
            log.warn("telemetry sequence gap을 확인했습니다. nodeId={}, bootId={}, previous={}, incoming={}",
                    state.getNodeId(), payload.getBootId(), state.getLastSequenceNo(), payload.getSequenceNo());
        }
        return TelemetryDeliveryDecision.ACCEPTED_CURRENT;
    }

    private String eventKey(String nodeId, Dht22Payload payload) {
        return nodeId + "|" + payload.getBootId() + "|" + payload.getSequenceNo();
    }

    private void saveOutbox(
            String eventKey,
            String nodeId,
            Dht22Payload payload,
            Instant receivedAt,
            TelemetryPointPayload pointPayload
    ) {
        telemetryOutboxRepository.save(new TelemetryOutbox(
                eventKey,
                nodeId,
                payload.getBootId(),
                payload.getSequenceNo(),
                receivedAt,
                serialize(pointPayload),
                TelemetryPointPayload.SCHEMA_VERSION
        ));
    }

    private String serialize(TelemetryPointPayload pointPayload) {
        try {
            return objectMapper.writeValueAsString(pointPayload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("InfluxDB outbox payload를 직렬화할 수 없습니다.", exception);
        }
    }
}
