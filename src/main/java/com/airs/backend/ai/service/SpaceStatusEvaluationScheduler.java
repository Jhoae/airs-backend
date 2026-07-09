package com.airs.backend.ai.service;

import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationPayload;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationResult;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.node.repository.NodeInstallationRepository;
import com.airs.backend.sensor.dto.AiSensorTrendData;
import com.airs.backend.sensor.dto.Dht22MeasurementItem;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.sensor.influx.InfluxDht22Reader;
import com.airs.backend.sensor.service.OccupancyFusionResult;
import com.airs.backend.status.entity.OccupancyStatus;
import com.airs.backend.status.entity.TelemetryOccupancyState;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "ai.evaluation.scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SpaceStatusEvaluationScheduler {

    private static final Logger log = LoggerFactory.getLogger(SpaceStatusEvaluationScheduler.class);

    private final NodeInstallationRepository nodeInstallationRepository;
    private final InfluxDht22Reader influxDht22Reader;
    private final SpaceEvaluationPayloadAssembler spaceEvaluationPayloadAssembler;
    private final SpaceStatusEvaluationService spaceStatusEvaluationService;
    private final SpaceEvaluationSnapshotWriter spaceEvaluationSnapshotWriter;
    private final SpaceEvaluationAlertService spaceEvaluationAlertService;

    @Scheduled(
            initialDelayString = "${ai.evaluation.scheduler.initial-delay-ms:60000}",
            fixedDelayString = "${ai.evaluation.scheduler.fixed-delay-ms:600000}"
    )
    public void evaluateActiveInstallations() {
        List<NodeInstallation> installations = nodeInstallationRepository.findAllByActiveTrue();
        Instant evaluatedAt = Instant.now();

        for (NodeInstallation installation : installations) {
            evaluateInstallation(installation, evaluatedAt);
        }
    }

    void evaluateInstallation(NodeInstallation installation, Instant evaluatedAt) {
        String nodeId = installation.getNode().getId();

        try {
            AiSensorTrendData trendData = influxDht22Reader.readAiSensorTrend(nodeId, evaluatedAt);
            Dht22MeasurementItem latestMeasurement = trendData.getLatestMeasurement();
            if (latestMeasurement == null) {
                log.debug("AI 평가에 사용할 InfluxDB 최신 측정값이 없습니다. nodeId={}", nodeId);
                return;
            }

            Dht22Payload payload = toPayload(latestMeasurement);
            OccupancyFusionResult occupancy = toOccupancy(trendData);
            SpaceEvaluationPayload evaluationPayload = spaceEvaluationPayloadAssembler.fromTelemetry(
                    installation,
                    payload,
                    occupancy,
                    trendData
            );
            SpaceEvaluationResult result = spaceStatusEvaluationService.evaluateSpaceStatus(evaluationPayload);
            spaceEvaluationSnapshotWriter.write(installation, payload, result);
            spaceEvaluationAlertService.syncAlerts(installation, result);
        } catch (RuntimeException exception) {
            log.warn("AI 공간 상태 평가를 건너뜁니다. nodeId={}, reason={}", nodeId, exception.getMessage());
        }
    }

    private Dht22Payload toPayload(Dht22MeasurementItem latestMeasurement) {
        return new Dht22Payload(
                latestMeasurement.getTemperature(),
                latestMeasurement.getHumidity(),
                latestMeasurement.getCo2Ppm(),
                latestMeasurement.getTimestamp()
        );
    }

    private OccupancyFusionResult toOccupancy(AiSensorTrendData trendData) {
        Integer noOccupancyMinutes = trendData.getNoOccupancyMinutes();
        return new OccupancyFusionResult(
                TelemetryOccupancyState.UNKNOWN,
                null,
                OccupancyStatus.UNKNOWN,
                null,
                noOccupancyMinutes == null ? null : noOccupancyMinutes.doubleValue(),
                noOccupancyMinutes != null
        );
    }
}
