package com.airs.backend.sensor.service;

import com.airs.backend.sensor.config.OccupancyProperties;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.status.entity.OccupancyStatus;
import com.airs.backend.status.entity.TelemetryOccupancyState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
// 노드별 PIR·mmWave 이력을 이용해 재실 상태를 융합한다.
public class OccupancyFusionService {

    // 부재 확정 대기 시간 등 재실 정책값을 읽는다.
    private final OccupancyProperties occupancyProperties;
    // 노드별 마지막 확정 움직임 시각을 메모리에 보관한다.
    private final Map<String, Instant> lastMotionByNodeId = new ConcurrentHashMap<>();
    // 움직임이 한 번도 없을 때 무움직임 시작 시각을 보관한다.
    private final Map<String, Instant> noMotionStartedByNodeId = new ConcurrentHashMap<>();
    // 두 번 연속 PIR 감지를 확인하기 위해 직전 PIR 값을 보관한다.
    private final Map<String, Boolean> previousPirByNodeId = new ConcurrentHashMap<>();

    // 하나의 telemetry payload를 현재 재실 상태와 저장값으로 변환한다.
    public OccupancyFusionResult resolve(String nodeId, Dht22Payload payload) {
        // 노드·payload·시각이 없으면 이력 기반 판정을 할 수 없다.
        if (nodeId == null || nodeId.isBlank() || payload == null || payload.getTimestamp() == null) {
            return unknown(false);
        }

        // 원본 PIR 값을 읽는다.
        Integer pir = payload.getPirDetected();
        // 원본 mmWave 값을 읽는다.
        Integer mmwave = payload.getMmwaveDetected();
        // 둘 중 하나라도 있으면 재실 센서 입력이 존재한다.
        boolean sourcePresent = pir != null || mmwave != null;
        // 재실 센서 값이 모두 없으면 추정하지 않고 UNKNOWN을 반환한다.
        if (!sourcePresent) {
            return unknown(false);
        }

        // payload 시각을 재실 이력 기준 시각으로 사용한다.
        Instant now = payload.getTimestamp();
        // 0이 아닌 PIR 값은 감지로 해석한다.
        boolean pirDetected = isDetected(pir);
        // 0이 아닌 mmWave 값은 감지로 해석한다.
        boolean mmwaveDetected = isDetected(mmwave);
        // 단발 PIR 오탐을 줄이기 위해 직전 값도 감지여야 확정한다.
        boolean pirConfirmed = pirDetected && previousPirByNodeId.getOrDefault(nodeId, false);
        // 다음 telemetry가 연속 PIR 여부를 판단할 수 있게 현재 값을 저장한다.
        previousPirByNodeId.put(nodeId, pirDetected);

        // mmWave 즉시 감지 또는 연속 PIR 감지는 현재 재실 근거다.
        if (mmwaveDetected || pirConfirmed) {
            // 감지 시각을 마지막 움직임으로 갱신한다.
            lastMotionByNodeId.put(nodeId, now);
            // 무움직임 시작 이력은 더 이상 필요 없으므로 제거한다.
            noMotionStartedByNodeId.remove(nodeId);
            // 방금 감지했으므로 경과 시간 0분의 재실 결과를 반환한다.
            return present(0.0);
        }

        // 이전에 확정된 마지막 움직임 시각을 찾는다.
        Instant lastMotionAt = lastMotionByNodeId.get(nodeId);
        // 아직 한 번도 감지한 적이 없을 때는 무움직임 시간부터 센다.
        if (lastMotionAt == null) {
            // 노드별 최초 무움직임 시각을 한 번만 기록한다.
            Instant noMotionStartedAt = noMotionStartedByNodeId.computeIfAbsent(nodeId, ignored -> now);
            // 무움직임 시작 뒤 지난 분을 계산한다.
            double minutesSinceNoMotion = Duration.between(noMotionStartedAt, now).toMillis() / 60000.0;
            // stale-after 전에는 부재로 단정하지 않고 UNKNOWN을 유지한다.
            if (minutesSinceNoMotion < occupancyProperties.getStaleAfterMinutes()) {
                return unknown(true);
            }
            // 충분한 무감지 시간이 지나면 부재 결과를 반환한다.
            return absent(roundOneDecimal(minutesSinceNoMotion));
        }

        // 마지막 확정 움직임 뒤 지난 분을 계산한다.
        double minutesSinceMotion = Duration.between(lastMotionAt, now).toMillis() / 60000.0;
        // stale-after 전에는 최근 움직임이 있다고 보고 재실을 유지한다.
        if (minutesSinceMotion < occupancyProperties.getStaleAfterMinutes()) {
            return present(roundOneDecimal(minutesSinceMotion));
        }
        // stale-after 이상이면 부재 결과를 반환한다.
        return absent(roundOneDecimal(minutesSinceMotion));
    }

    // 0이 아닌 센서 정수값을 감지로 해석한다.
    private boolean isDetected(Integer value) {
        return value != null && value != 0;
    }

    // 재실 결과를 telemetry·snapshot 저장 형식으로 조립한다.
    private OccupancyFusionResult present(Double minutesSinceMotion) {
        return new OccupancyFusionResult(
                TelemetryOccupancyState.PRESENT,
                true,
                OccupancyStatus.OCCUPIED,
                1,
                minutesSinceMotion,
                true
        );
    }

    // 부재 결과를 telemetry·snapshot 저장 형식으로 조립한다.
    private OccupancyFusionResult absent(Double minutesSinceMotion) {
        return new OccupancyFusionResult(
                TelemetryOccupancyState.ABSENT,
                false,
                OccupancyStatus.UNOCCUPIED,
                0,
                minutesSinceMotion,
                true
        );
    }

    // 판정 근거가 부족한 결과를 telemetry·snapshot 저장 형식으로 조립한다.
    private OccupancyFusionResult unknown(boolean sourcePresent) {
        return new OccupancyFusionResult(
                TelemetryOccupancyState.UNKNOWN,
                null,
                OccupancyStatus.UNKNOWN,
                null,
                null,
                sourcePresent
        );
    }

    // 화면과 저장소의 숫자 표현을 통일하도록 분 값을 소수 첫째 자리로 반올림한다.
    private double roundOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
