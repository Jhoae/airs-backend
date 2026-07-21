package com.airs.backend.ai.service;

import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationPayload;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationResult;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.node.repository.NodeInstallationRepository;
import com.airs.backend.sensor.dto.AiSensorTrendData;
import com.airs.backend.sensor.dto.Dht22MeasurementItem;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.sensor.influx.InfluxDht22Reader;
import com.airs.backend.sensor.influx.InfluxDht22Writer;
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

/**
 * 활성 설치 노드를 10분마다 평가하는 현재 Spring 내부 AI 파이프라인의 시작점이다.
 *
 * <p>예를 들어 {@code node_01}의 최근 telemetry를 InfluxDB에서 읽어 평가하고,
 * 결과를 {@code space_status_snapshots}와 {@code alerts} 테이블에 반영한다.</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "ai.evaluation.scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SpaceStatusEvaluationScheduler {

    // 한 노드 평가 실패가 전체 스케줄을 멈추지 않도록 원인을 기록한다.
    private static final Logger log = LoggerFactory.getLogger(SpaceStatusEvaluationScheduler.class);

    // active=true 설치만 읽어, 삭제되었거나 이전된 노드는 평가 대상에서 제외한다.
    private final NodeInstallationRepository nodeInstallationRepository;
    // InfluxDB raw telemetry에서 최신값과 10/30분 추세를 계산해 읽는다.
    private final InfluxDht22Reader influxDht22Reader;
    // telemetry DTO를 규칙 평가기가 이해하는 current/trend 입력값으로 변환한다.
    private final SpaceEvaluationPayloadAssembler spaceEvaluationPayloadAssembler;
    // comfort, 환기, 냉난방 낭비를 순수 Java 규칙으로 판정한다.
    private final SpaceStatusEvaluationService spaceStatusEvaluationService;
    // 판정 결과를 공간당 최신 한 행인 space_status_snapshots에 upsert한다.
    private final SpaceEvaluationSnapshotWriter spaceEvaluationSnapshotWriter;
    // 평가 시각별 Comfort Score를 InfluxDB 그래프 이력으로 기록한다.
    private final InfluxDht22Writer influxDht22Writer;
    // 환기/냉난방 판정에 따라 ACTIVE 알림을 생성·갱신·해결한다.
    private final SpaceEvaluationAlertService spaceEvaluationAlertService;

    @Scheduled(
            initialDelayString = "${ai.evaluation.scheduler.initial-delay-ms:60000}",
            fixedDelayString = "${ai.evaluation.scheduler.fixed-delay-ms:600000}"
    )
    public void evaluateActiveInstallations() {
        // 예: node_01이 K301에 active 설치된 경우만 목록에 포함된다.
        List<NodeInstallation> installations = nodeInstallationRepository.findAllByActiveTrue();
        // 같은 실행 주기 안에서 모든 노드에 동일한 평가 기준 시각을 사용한다.
        Instant evaluatedAt = Instant.now();

        // 한 노드가 실패해도 다음 노드의 snapshot/alert 갱신은 계속한다.
        for (NodeInstallation installation : installations) {
            evaluateInstallation(installation, evaluatedAt);
        }
    }

    void evaluateInstallation(NodeInstallation installation, Instant evaluatedAt) {
        // installation -> AirsNode 관계에서 MQTT/Influx tag와 같은 node ID를 얻는다.
        String nodeId = installation.getNode().getId();

        try {
            // 최근 30분 raw telemetry로 최신 측정값, CO2 변화량, 고농도 지속시간을 만든다.
            AiSensorTrendData trendData = influxDht22Reader.readAiSensorTrend(nodeId, evaluatedAt);
            // 예: {temperature=24.3, humidity=52.0, co2=842, timestamp=...}를 꺼낸다.
            Dht22MeasurementItem latestMeasurement = trendData.getLatestMeasurement();
            if (latestMeasurement == null) {
                // 아직 publish 이력이 없는 노드는 빈 snapshot이나 잘못된 알림을 만들지 않는다.
                log.debug("AI 평가에 사용할 InfluxDB 최신 측정값이 없습니다. nodeId={}", nodeId);
                return;
            }

            // Influx 조회 DTO를 이후 writer가 공통으로 사용하는 telemetry payload로 맞춘다.
            Dht22Payload payload = toPayload(latestMeasurement);
            // 현재 구현은 trend의 무재실 시간을 전달하고, PIR/mmWave 확정값이 없으면 UNKNOWN으로 둔다.
            OccupancyFusionResult occupancy = toOccupancy(trendData);
            // 설치 공간 ID + 현재 센서값 + 시간 추세를 규칙 함수의 입력 record로 조립한다.
            SpaceEvaluationPayload evaluationPayload = spaceEvaluationPayloadAssembler.fromTelemetry(
                    installation,
                    payload,
                    occupancy,
                    trendData
            );
            // comfort score, 환기 권장, 냉난방 낭비 여부를 계산한 불변 결과 record를 만든다.
            SpaceEvaluationResult result = spaceStatusEvaluationService.evaluateSpaceStatus(evaluationPayload);
            // MySQL space_status_snapshots의 해당 space 한 행에 최신 센서값과 comfort 결과를 저장한다.
            spaceEvaluationSnapshotWriter.write(installation, payload, result);
            // 그래프용 Comfort Score 이력의 실패가 snapshot·alert 갱신을 막지 않게 별도로 기록한다.
            writeComfortScoreSafely(nodeId, result, evaluatedAt);
            // MySQL alerts에서 같은 node/type ACTIVE 알림을 생성·갱신하거나 해결 처리한다.
            spaceEvaluationAlertService.syncAlerts(installation, result);
        } catch (RuntimeException exception) {
            // Influx/DB 한 건의 실패만 남기고 다음 설치 노드 평가를 이어간다.
            log.warn("AI 공간 상태 평가를 건너뜁니다. nodeId={}, reason={}", nodeId, exception.getMessage());
        }
    }

    // Comfort Score 저장 실패를 운영 상태 판단 실패로 번지지 않게 격리합니다.
    private void writeComfortScoreSafely(String nodeId, SpaceEvaluationResult result, Instant evaluatedAt) {
        try {
            // 규칙 함수가 계산한 0~100 점수를 실제 평가 시각에 기록합니다.
            influxDht22Writer.writeComfortScore(nodeId, result.comfort().score(), evaluatedAt);
        } catch (RuntimeException exception) {
            // 다음 주기에 다시 적재할 수 있도록 실패만 남기고 평가 흐름은 유지합니다.
            log.warn("Comfort Score 이력 저장에 실패했습니다. nodeId={}, reason={}", nodeId, exception.getMessage());
        }
    }

    private Dht22Payload toPayload(Dht22MeasurementItem latestMeasurement) {
        // Influx의 마지막 row를 MQTT 수신 때 쓰는 공통 payload 형태로 변환한다.
        return new Dht22Payload(
                // 예: 24.3°C
                latestMeasurement.getTemperature(),
                // 예: 52.0%
                latestMeasurement.getHumidity(),
                // 예: 842 ppm
                latestMeasurement.getCo2Ppm(),
                // snapshot의 최근 수신 시각으로 보존할 telemetry 원본 시각
                latestMeasurement.getTimestamp()
        );
    }

    private OccupancyFusionResult toOccupancy(AiSensorTrendData trendData) {
        // Influx에 이미 계산되어 있는 마지막 무재실 지속 시간을 읽는다.
        Integer noOccupancyMinutes = trendData.getNoOccupancyMinutes();
        return new OccupancyFusionResult(
                // 이 스케줄 경로에서는 원시 PIR/mmWave를 다시 확정하지 않으므로 UNKNOWN으로 둔다.
                TelemetryOccupancyState.UNKNOWN,
                // 확정된 사람 감지 boolean은 없음을 명시한다.
                null,
                // status snapshot에는 평가 서비스가 추가로 도출한 값을 기록한다.
                OccupancyStatus.UNKNOWN,
                // 마지막 움직임 시각을 직접 보유하지 않는다.
                null,
                // 예: 12분이면 12.0, 데이터가 없으면 null을 전달한다.
                noOccupancyMinutes == null ? null : noOccupancyMinutes.doubleValue(),
                // noOccupancyMinutes가 존재할 때만 이 값이 신뢰할 이력임을 표시한다.
                noOccupancyMinutes != null
        );
    }
}
