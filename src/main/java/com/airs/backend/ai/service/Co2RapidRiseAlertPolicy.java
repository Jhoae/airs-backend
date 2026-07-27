package com.airs.backend.ai.service;

import org.springframework.stereotype.Component;

/**
 * 절대 CO2 기준과 별개로, 짧은 시간에 빠르게 상승하는 상황만 경고로 승격하는 정책이다.
 */
@Component
public class Co2RapidRiseAlertPolicy {

    // 800ppm 이하는 정상 구간이므로 변화량 알림 대상에서 제외한다.
    private static final int MIN_CO2_PPM = 801;
    // 1000ppm 초과는 기존 절대값 기반 환기 알림이 담당한다.
    private static final int MAX_CO2_PPM = 1000;
    // 새 알림은 10분 동안 100ppm 이상 상승했을 때만 만든다.
    private static final double ACTIVATE_RATE_PPM_PER_10_MINUTES = 100.0;
    // 이미 열린 알림은 50ppm을 초과하는 동안 유지해 경계값 반복으로 인한 상태 전환을 줄인다.
    private static final double KEEP_ACTIVE_RATE_PPM_PER_10_MINUTES = 50.0;

    // 현재 측정값과 기존 활성 여부로 생성·유지·해결·판단유보 중 하나를 결정한다.
    public Decision decide(
            Integer co2Ppm,
            Double co2Rate10m,
            boolean alreadyActive
    ) {
        // CO2 또는 변화량 근거가 없으면 정상화로 단정하지 않고 현재 알림을 그대로 둔다.
        if (co2Ppm == null || co2Rate10m == null) {
            return Decision.KEEP_UNCHANGED;
        }

        // 정상 CO2 또는 절대값 환기 알림 구간이면 급상승 알림을 유지할 이유가 없다.
        if (co2Ppm < MIN_CO2_PPM || co2Ppm > MAX_CO2_PPM) {
            return Decision.RESOLVE;
        }

        // 새 경고는 CO2가 801~1000ppm이면서 10분 상승폭이 100ppm 이상일 때만 생성한다.
        if (!alreadyActive && co2Rate10m >= ACTIVATE_RATE_PPM_PER_10_MINUTES) {
            return Decision.ACTIVATE;
        }

        // 기존 경고는 상승폭이 50ppm을 초과하는 동안 최신 수치로 갱신한다.
        if (alreadyActive && co2Rate10m > KEEP_ACTIVE_RATE_PPM_PER_10_MINUTES) {
            return Decision.KEEP_ACTIVE;
        }

        // 새 경고 생성 기준에만 도달했으면 새 row를 만들지 않고 현재 상태를 그대로 둔다.
        if (!alreadyActive) {
            return Decision.KEEP_UNCHANGED;
        }

        // 상승폭이 50ppm 이하로 내려가면 기존 ACTIVE 알림을 해결 처리한다.
        if (co2Rate10m <= KEEP_ACTIVE_RATE_PPM_PER_10_MINUTES) {
            return Decision.RESOLVE;
        }

        // 위 분기에서 모두 반환했으므로 방어적으로 현재 상태를 유지한다.
        return Decision.KEEP_UNCHANGED;
    }

    // 알림 lifecycle에서 필요한 네 가지 명시적 결정을 표현한다.
    public enum Decision {
        // 새 ACTIVE alerts 행을 만든다.
        ACTIVATE,
        // 기존 ACTIVE 행을 최신 변화량으로 갱신한다.
        KEEP_ACTIVE,
        // 기존 ACTIVE 행을 RESOLVED 이력으로 전환한다.
        RESOLVE,
        // 근거 부족 또는 경고 미도달이므로 기존 상태를 바꾸지 않는다.
        KEEP_UNCHANGED
    }
}
