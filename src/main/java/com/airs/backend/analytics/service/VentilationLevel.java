package com.airs.backend.analytics.service;

enum VentilationLevel {

    // 환기 조치가 필요 없는 상태다.
    GOOD,
    // 환기를 권장하는 상태다.
    RECOMMENDED,
    // 즉시 환기가 필요한 상태다.
    NEEDED,
    // CO2 데이터가 없어 판단할 수 없는 상태다.
    NO_DATA;

    static VentilationLevel from(Integer co2Ppm) {
        // 값이 없으면 환기 여부를 추측하지 않는다.
        if (co2Ppm == null) {
            return NO_DATA;
        }
        // 1,000ppm 이하는 환기 양호로 분류한다.
        if (co2Ppm <= 1_000) {
            return GOOD;
        }
        // 1,500ppm 이하는 환기 권장으로 분류한다.
        if (co2Ppm <= 1_500) {
            return RECOMMENDED;
        }
        // 그보다 높으면 환기 필요로 분류한다.
        return NEEDED;
    }
}
