package com.airs.backend.sensor.service;

import com.airs.backend.ai.service.SpaceEvaluationPayloadAssembler;
import com.airs.backend.ai.service.SpaceStatusEvaluationService;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.Co2Status;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationPayload;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationResult;
import com.airs.backend.alert.service.WeakWifiAlertService;
import com.airs.backend.node.entity.AirsNode;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.node.repository.NodeInstallationRepository;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.status.entity.ConnectionStatus;
import com.airs.backend.status.entity.NodeStatusSnapshot;
import com.airs.backend.status.entity.SensorStatus;
import com.airs.backend.status.entity.SpaceStatusSnapshot;
import com.airs.backend.status.repository.NodeStatusSnapshotRepository;
import com.airs.backend.status.repository.SpaceStatusSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;

// telemetry마다 MySQL의 노드·공간 최신 상태 snapshot을 갱신하는 서비스입니다.
@Service
// repository와 상태 평가 의존성을 생성자로 주입합니다.
@RequiredArgsConstructor
public class Dht22SnapshotUpdateService {

    // snapshot 갱신을 건너뛴 노드와 오류 원인을 기록합니다.
    private static final Logger log = LoggerFactory.getLogger(Dht22SnapshotUpdateService.class);
    // MQTT 수신 시각을 MySQL LocalDateTime으로 변환할 서비스 표준 시간대입니다.
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    // 현재 활성 설치와 연결된 노드·공간을 찾습니다.
    private final NodeInstallationRepository nodeInstallationRepository;
    // 노드별 연결·센서·Wi-Fi 최신 상태를 저장합니다.
    private final NodeStatusSnapshotRepository nodeStatusSnapshotRepository;
    // 공간별 환경·재실·AI 평가 최신 상태를 저장합니다.
    private final SpaceStatusSnapshotRepository spaceStatusSnapshotRepository;
    // 센서 움직임을 재실 상태로 융합합니다.
    private final OccupancyFusionService occupancyFusionService;
    // telemetry를 공간 상태 평가 입력값으로 조립합니다.
    private final SpaceEvaluationPayloadAssembler spaceEvaluationPayloadAssembler;
    // 현재 센서값으로 comfort·CO2 상태를 평가합니다.
    private final SpaceStatusEvaluationService spaceStatusEvaluationService;
    // 실제 Wi-Fi RSSI로 정보 알림 lifecycle을 갱신합니다.
    private final WeakWifiAlertService weakWifiAlertService;

    // 재실 결과를 아직 계산하지 않은 호출의 최신 snapshot을 트랜잭션으로 갱신합니다.
    @Transactional
    public void updateLatestSnapshot(String nodeId, Dht22Payload payload) {
        // 현재 이 노드가 설치된 활성 공간을 조회합니다.
        NodeInstallation installation = findActiveInstallation(nodeId);
        // 설치되지 않은 테스트 노드는 공간·노드 snapshot을 만들지 않습니다.
        if (installation == null) {
            return;
        }

        // 현재 payload로 재실 상태를 계산한 뒤 두 snapshot을 갱신합니다.
        updateSnapshot(installation, payload, occupancyFusionService.resolve(nodeId, payload));
    }

    // 이미 계산한 재실 결과를 재사용해 최신 snapshot을 트랜잭션으로 갱신합니다.
    @Transactional
    public void updateLatestSnapshot(String nodeId, Dht22Payload payload, OccupancyFusionResult occupancy) {
        // 현재 이 노드가 설치된 활성 공간을 조회합니다.
        NodeInstallation installation = findActiveInstallation(nodeId);
        // 설치되지 않은 노드는 사용자 화면용 snapshot 갱신 대상이 아닙니다.
        if (installation == null) {
            return;
        }

        // 수신 서비스에서 계산한 재실 결과로 두 snapshot을 갱신합니다.
        updateSnapshot(installation, payload, occupancy);
    }

    // 노드에 연결된 활성 설치 정보를 조회합니다.
    private NodeInstallation findActiveInstallation(String nodeId) {
        // active=true인 설치만 최신 사용자 공간 상태에 반영합니다.
        NodeInstallation installation = nodeInstallationRepository.findByNode_IdAndActiveTrue(nodeId)
                .orElse(null);
        // 미설치 노드는 InfluxDB raw만 보존하고 MySQL snapshot은 건너뜁니다.
        if (installation == null) {
            log.debug("active 설치가 없는 노드라 MySQL snapshot 갱신을 건너뜁니다. nodeId={}", nodeId);
        }
        // 조회한 활성 설치 또는 null을 반환합니다.
        return installation;
    }

    private void updateSnapshot(
            NodeInstallation installation,
            Dht22Payload payload,
            OccupancyFusionResult occupancy
    ) {
        // UTC Instant 수신 시각을 서비스 화면과 같은 한국 시간 LocalDateTime으로 변환합니다.
        LocalDateTime receivedAt = LocalDateTime.ofInstant(payload.getTimestamp(), SERVICE_ZONE);
        // 노드 연결·센서 상태 snapshot을 최신 telemetry로 갱신합니다.
        updateNodeStatus(installation.getNode(), payload, occupancy, receivedAt);
        // telemetry가 실제로 보낸 Wi-Fi RSSI로 약함 정보 알림을 즉시 동기화합니다.
        weakWifiAlertService.sync(installation, payload.getWifiSignalDbm(), receivedAt);
        // 연결된 공간의 환경·재실·AI 평가 snapshot을 최신 telemetry로 갱신합니다.
        updateSpaceStatus(installation, payload, occupancy, receivedAt);
    }

    private void updateNodeStatus(
            AirsNode node,
            Dht22Payload payload,
            OccupancyFusionResult occupancy,
            LocalDateTime receivedAt
    ) {
        // DHT22 상태 문자열을 DB 길이에 맞게 정규화합니다.
        String dht22Status = normalizeStatus(payload.getDht22Status());
        // SCD41 상태 문자열을 DB 길이에 맞게 정규화합니다.
        String scd41Status = normalizeStatus(payload.getScd41Status());
        // 두 센서 상태로 노드의 종합 센서 정상 여부를 계산합니다.
        SensorStatus sensorStatus = resolveSensorStatus(dht22Status, scd41Status);

        // 기존 node snapshot이 있으면 수신 시각과 최신 상태만 갱신하고 없으면 새로 만듭니다.
        nodeStatusSnapshotRepository.findByNode_Id(node.getId())
                .ifPresentOrElse(
                        nodeStatus -> nodeStatus.markSensorReceived(
                                receivedAt,
                                sensorStatus,
                                dht22Status,
                                scd41Status,
                                resolveWifiRssi(payload, nodeStatus),
                                resolveHumanDetected(occupancy, nodeStatus)
                        ),
                        () -> nodeStatusSnapshotRepository.save(new NodeStatusSnapshot(
                                node,
                                ConnectionStatus.ONLINE,
                                sensorStatus,
                                dht22Status,
                                scd41Status,
                                payload.getWifiSignalDbm(),
                                occupancy.humanDetected(),
                                receivedAt,
                                receivedAt
                        ))
                );
    }

    // 이번 payload에 Wi-Fi 값이 없으면 기존 최신 RSSI를 유지합니다.
    private Integer resolveWifiRssi(Dht22Payload payload, NodeStatusSnapshot nodeStatus) {
        return payload.getWifiSignalDbm() == null ? nodeStatus.getWifiRssi() : payload.getWifiSignalDbm();
    }

    // 이번 telemetry에 재실 근거가 없으면 기존 사람 감지 상태를 유지합니다.
    private Boolean resolveHumanDetected(OccupancyFusionResult occupancy, NodeStatusSnapshot nodeStatus) {
        return occupancy.sourcePresent() ? occupancy.humanDetected() : nodeStatus.getHumanDetected();
    }

    private void updateSpaceStatus(
            NodeInstallation installation,
            Dht22Payload payload,
            OccupancyFusionResult occupancy,
            LocalDateTime receivedAt
    ) {
        // 화면과 DB에서 일정한 소수 둘째 자리로 온도를 저장합니다.
        BigDecimal temperature = toScaledBigDecimal(payload.getTemperature());
        // 화면과 DB에서 일정한 소수 둘째 자리로 습도를 저장합니다.
        BigDecimal humidity = toScaledBigDecimal(payload.getHumidity());

        // 공간 snapshot이 있으면 갱신하고 없으면 현재 설치 정보를 기준으로 새로 생성합니다.
        spaceStatusSnapshotRepository.findBySpace_Id(installation.getSpace().getId())
                .ifPresentOrElse(
                        spaceStatus -> updateExistingSpaceStatus(
                                spaceStatus,
                                installation,
                                temperature,
                                humidity,
                                payload,
                                occupancy,
                                receivedAt
                        ),
                        () -> spaceStatusSnapshotRepository.save(createSpaceStatusSnapshot(
                                installation,
                                temperature,
                                humidity,
                                payload,
                                occupancy,
                                receivedAt
                        ))
                );
    }

    private SpaceStatusSnapshot createSpaceStatusSnapshot(
            NodeInstallation installation,
            BigDecimal temperature,
            BigDecimal humidity,
            Dht22Payload payload,
            OccupancyFusionResult occupancy,
            LocalDateTime receivedAt
    ) {
        // 현재 환경값과 재실 결과를 담은 공간 최신 상태 엔티티를 생성합니다.
        SpaceStatusSnapshot spaceStatus = new SpaceStatusSnapshot(
                installation.getSpace(),
                installation.getNode(),
                temperature,
                humidity,
                payload.getCo2Ppm(),
                occupancy.sourcePresent() ? occupancy.humanDetected() : null,
                occupancy.sourcePresent() ? occupancy.occupancyStatus() : null,
                null,
                receivedAt
        );
        // 새 공간 상태에도 comfort·CO2 요약 평가를 채웁니다.
        updateAiEvaluation(spaceStatus, installation, payload, occupancy);
        // 평가까지 완료된 새 공간 snapshot을 반환합니다.
        return spaceStatus;
    }

    private void updateExistingSpaceStatus(
            SpaceStatusSnapshot spaceStatus,
            NodeInstallation installation,
            BigDecimal temperature,
            BigDecimal humidity,
            Dht22Payload payload,
            OccupancyFusionResult occupancy,
            LocalDateTime receivedAt
    ) {
        // 이번 telemetry에 재실 판단 근거가 있으면 재실 field까지 함께 갱신합니다.
        if (occupancy.sourcePresent()) {
            spaceStatus.updateLatestSensorValues(
                    installation.getNode(),
                    temperature,
                    humidity,
                    payload.getCo2Ppm(),
                    occupancy.humanDetected(),
                    occupancy.occupancyStatus(),
                    receivedAt
            );
            // 갱신된 센서값으로 comfort·CO2 요약 평가를 다시 계산합니다.
            updateAiEvaluation(spaceStatus, installation, payload, occupancy);
            // 재실 field까지 갱신한 경로를 종료합니다.
            return;
        }

        // 재실 근거가 없으면 기존 재실 상태를 유지한 채 환경값만 갱신합니다.
        spaceStatus.updateLatestSensorValues(
                installation.getNode(),
                temperature,
                humidity,
                payload.getCo2Ppm(),
                receivedAt
        );
        // 환경값 갱신 후 comfort·CO2 요약 평가를 다시 계산합니다.
        updateAiEvaluation(spaceStatus, installation, payload, occupancy);
    }

    private void updateAiEvaluation(
            SpaceStatusSnapshot spaceStatus,
            NodeInstallation installation,
            Dht22Payload payload,
            OccupancyFusionResult occupancy
    ) {
        // 설치 위치·현재 telemetry·재실 결과를 Java 정책 평가용 입력으로 조립합니다.
        SpaceEvaluationPayload evaluationPayload = spaceEvaluationPayloadAssembler.fromTelemetry(
                installation,
                payload,
                occupancy
        );
        // 조립한 입력값으로 comfort 점수와 CO2 상태를 계산합니다.
        SpaceEvaluationResult result = spaceStatusEvaluationService.evaluateSpaceStatus(evaluationPayload);

        // 계산 결과를 공간 최신 상태에 저장해 목록·상세 API가 즉시 읽게 합니다.
        spaceStatus.updateAiEvaluation(
                BigDecimal.valueOf(result.comfort().score()).setScale(2, RoundingMode.HALF_UP),
                result.comfort().labelKo(),
                toCo2Summary(result.ventilation().co2Status())
        );
    }

    // 내부 CO2 enum을 앱·웹에 노출하는 한국어 요약 문자열로 변환합니다.
    private String toCo2Summary(Co2Status co2Status) {
        // 모든 CO2 상태를 UI 계약의 한국어 텍스트로 명시적으로 매핑합니다.
        return switch (co2Status) {
            case GOOD -> "좋음";
            case NORMAL -> "보통";
            case WARNING -> "주의";
            case BAD -> "나쁨";
            case UNKNOWN -> "데이터 없음";
        };
    }

    // 센서 실수를 DB 저장용 소수 둘째 자리 BigDecimal로 변환합니다.
    private BigDecimal toScaledBigDecimal(Double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    // 두 센서가 OK 또는 미보고면 정상으로, 그 외 상태가 있으면 비정상으로 판단합니다.
    private SensorStatus resolveSensorStatus(String dht22Status, String scd41Status) {
        // 두 센서 상태가 모두 정상 규칙을 만족하는지 확인합니다.
        if (isOkOrMissing(dht22Status) && isOkOrMissing(scd41Status)) {
            return SensorStatus.NORMAL;
        }
        return SensorStatus.ABNORMAL;
    }

    // 센서 상태가 미보고이거나 대소문자와 무관하게 OK인지 판별합니다.
    private boolean isOkOrMissing(String status) {
        return status == null || "OK".equalsIgnoreCase(status);
    }

    // 외부 센서 상태 문자열을 null·공백·최대 길이 규칙에 맞게 정리합니다.
    private String normalizeStatus(String status) {
        // null 또는 공백은 상태 미보고로 저장합니다.
        if (status == null || status.isBlank()) {
            return null;
        }

        // 앞뒤 공백을 제거한 실제 상태 문자열을 만듭니다.
        String trimmed = status.trim();
        // DB 컬럼 길이를 넘지 않는 상태 문자열은 그대로 사용합니다.
        if (trimmed.length() <= 30) {
            return trimmed;
        }
        // 너무 긴 외부 상태 문자열은 DB 컬럼 길이에 맞춰 자릅니다.
        return trimmed.substring(0, 30);
    }
}
