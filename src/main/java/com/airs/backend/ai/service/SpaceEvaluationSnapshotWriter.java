package com.airs.backend.ai.service;

import com.airs.backend.ai.service.SpaceStatusEvaluationService.Co2Status;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.OccupancyState;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationResult;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.status.entity.OccupancyStatus;
import com.airs.backend.status.entity.SpaceStatusSnapshot;
import com.airs.backend.status.repository.SpaceStatusSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 규칙 평가 결과를 공간당 한 행인 {@code space_status_snapshots}에 저장한다.
 * raw telemetry는 InfluxDB에 계속 누적되고, 이 클래스는 목록/대시보드가 빠르게 읽을 최신 상태만 MySQL에 둔다.
 */
@Service
@RequiredArgsConstructor
public class SpaceEvaluationSnapshotWriter {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    // space_id를 기준으로 기존 최신 상태 행을 찾아 update 또는 insert한다.
    private final SpaceStatusSnapshotRepository spaceStatusSnapshotRepository;

    @Transactional
    public void write(
            NodeInstallation installation,
            Dht22Payload payload,
            SpaceEvaluationResult result
    ) {
        // Influx point의 센서 측정 시각을 MySQL DATETIME에 저장할 한국 서비스 기준 시각으로 변환한다.
        LocalDateTime observedAt = LocalDateTime.ofInstant(payload.getObservedAt(), SERVICE_ZONE);

        // 같은 space에는 snapshot 한 행만 존재해야 하므로 space_id로 먼저 찾는다.
        spaceStatusSnapshotRepository.findBySpace_Id(installation.getSpace().getId())
                .ifPresentOrElse(
                        // 행이 있으면 예: K301의 temperature/humidity/co2/comfort 값을 최신 telemetry로 갱신한다.
                        snapshot -> updateSnapshot(snapshot, installation, payload, result, observedAt),
                        // 처음 설치된 공간이면 센서 최신값과 평가 결과를 가진 새 행을 INSERT한다.
                        () -> spaceStatusSnapshotRepository.save(createSnapshot(
                                installation,
                                payload,
                                result,
                                observedAt
                        ))
                );
    }

    private SpaceStatusSnapshot createSnapshot(
            NodeInstallation installation,
            Dht22Payload payload,
            SpaceEvaluationResult result,
            LocalDateTime observedAt
    ) {
        // 생성자는 space, 대표 node, 현재 측정값, 재실 상태, 수신 시각을 초기화한다.
        SpaceStatusSnapshot snapshot = new SpaceStatusSnapshot(
                // 이 결과가 표시될 공간 FK, 예: K301
                installation.getSpace(),
                // 해당 공간의 대표/최근 평가 노드 FK, 예: node_01
                installation.getNode(),
                // MySQL DECIMAL(?,2)에 맞춘 온도, 예: 24.30
                toScaledBigDecimal(payload.getTemperature()),
                // MySQL DECIMAL(?,2)에 맞춘 습도, 예: 52.00
                toScaledBigDecimal(payload.getHumidity()),
                // CO2는 ppm 정수, 예: 842
                payload.getCo2Ppm(),
                // PRESENT면 true, ABSENT면 false, 모르면 null
                toHumanDetected(result),
                // OCCUPIED/UNOCCUPIED/UNKNOWN으로 변환한 재실 상태
                toOccupancyStatus(result),
                // 이 단계에서는 별도 예약 컬럼을 사용하지 않는다.
                null,
                // InfluxDB에서 읽은 실제 센서 측정 시각
                observedAt,
                // Influx 조회 결과에는 Spring 최초 수신 시각이 없으므로 값을 추측하지 않는다.
                null
        );
        // INSERT 직전 comfortScore, comfortSummary, co2Summary도 같은 snapshot 행에 채운다.
        updateAiEvaluation(snapshot, result);
        return snapshot;
    }

    private void updateSnapshot(
            SpaceStatusSnapshot snapshot,
            NodeInstallation installation,
            Dht22Payload payload,
            SpaceEvaluationResult result,
            LocalDateTime observedAt
    ) {
        // 기존 snapshot 행의 대표 노드와 최신 센서 필드만 telemetry 기준으로 바꾼다.
        snapshot.updateLatestSensorValues(
                installation.getNode(),
                toScaledBigDecimal(payload.getTemperature()),
                toScaledBigDecimal(payload.getHumidity()),
                payload.getCo2Ppm(),
                toHumanDetected(result),
                toOccupancyStatus(result),
                observedAt,
                // AI 재평가는 새 MQTT 수신이 아니므로 기존 processing time을 보존한다.
                snapshot.getLastReceivedAt()
        );
        // 센서 값 갱신과 같은 트랜잭션에서 평가 요약 필드도 일관되게 갱신한다.
        updateAiEvaluation(snapshot, result);
    }

    private void updateAiEvaluation(SpaceStatusSnapshot snapshot, SpaceEvaluationResult result) {
        // score 86을 86.00처럼 소수 둘째 자리 DECIMAL로 저장한다.
        snapshot.updateAiEvaluation(
                // comfort score는 목록/상세/분석 UI가 MySQL에서 빠르게 읽는 값이다.
                BigDecimal.valueOf(result.comfort().score()).setScale(2, RoundingMode.HALF_UP),
                // 예: GOOD -> "쾌적", NORMAL -> "보통"
                result.comfort().labelKo(),
                // 예: 842ppm -> "보통", 1,501ppm -> "나쁨"
                toCo2Summary(result.ventilation().co2Status())
        );
    }

    private OccupancyStatus toOccupancyStatus(SpaceEvaluationResult result) {
        // 규칙 서비스의 내부 재실 상태를 DB enum으로 번역한다.
        OccupancyState occupancyState = result.reportSummaryValues().occupancyState();
        return switch (occupancyState) {
            case PRESENT -> OccupancyStatus.OCCUPIED;
            case ABSENT -> OccupancyStatus.UNOCCUPIED;
            case UNKNOWN -> OccupancyStatus.UNKNOWN;
        };
    }

    private Boolean toHumanDetected(SpaceEvaluationResult result) {
        // Boolean 컬럼은 센서·융합 결과가 UNKNOWN일 때 null을 유지해 거짓으로 오해하지 않게 한다.
        OccupancyState occupancyState = result.reportSummaryValues().occupancyState();
        return switch (occupancyState) {
            case PRESENT -> true;
            case ABSENT -> false;
            case UNKNOWN -> null;
        };
    }

    private String toCo2Summary(Co2Status co2Status) {
        // UI가 추가 임계값 계산 없이 표시할 한국어 상태 라벨을 MySQL에 저장한다.
        return switch (co2Status) {
            case GOOD -> "좋음";
            case NORMAL -> "보통";
            case WARNING -> "주의";
            case BAD -> "나쁨";
            case UNKNOWN -> "데이터 없음";
        };
    }

    private BigDecimal toScaledBigDecimal(Double value) {
        // telemetry가 없는 필드는 0으로 만들지 않고 데이터 없음(null)으로 저장한다.
        if (value == null) {
            return null;
        }
        // binary floating-point 오차를 줄이고 DB 숫자 표현을 통일한다.
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
