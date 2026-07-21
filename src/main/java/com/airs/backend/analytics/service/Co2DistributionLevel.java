package com.airs.backend.analytics.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
enum Co2DistributionLevel {

    // 800ppm 이하의 쾌적한 CO2 구간이다.
    GOOD("좋음", "≤ 800 ppm"),
    // 801~1,000ppm의 보통 CO2 구간이다.
    NORMAL("보통", "801~1,000 ppm"),
    // 1,001~1,500ppm의 주의 CO2 구간이다.
    WARNING("주의", "1,001~1,500 ppm"),
    // 1,500ppm 초과의 나쁨 CO2 구간이다.
    BAD("나쁨", "> 1,500 ppm"),
    // 최신 CO2 값이 없을 때 사용하는 구간이다.
    NO_DATA("데이터 없음", "-");

    // 화면에 표시할 구간 이름이다.
    private final String label;
    // 화면에 표시할 ppm 범위다.
    private final String rangeLabel;

    static Co2DistributionLevel from(Integer co2Ppm) {
        // 값이 없으면 수치를 추정하지 않고 데이터 없음으로 분류한다.
        if (co2Ppm == null) {
            return NO_DATA;
        }
        // 800ppm 이하는 좋음으로 분류한다.
        if (co2Ppm <= 800) {
            return GOOD;
        }
        // 1,000ppm 이하는 보통으로 분류한다.
        if (co2Ppm <= 1_000) {
            return NORMAL;
        }
        // 1,500ppm 이하는 주의로 분류한다.
        if (co2Ppm <= 1_500) {
            return WARNING;
        }
        // 남은 값은 모두 나쁨으로 분류한다.
        return BAD;
    }
}
