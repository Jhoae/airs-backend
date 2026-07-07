package com.airs.backend.analytics.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
enum Co2DistributionLevel {

    GOOD("좋음", "≤ 800 ppm"),
    NORMAL("보통", "801~1,000 ppm"),
    WARNING("주의", "1,001~1,500 ppm"),
    BAD("나쁨", "> 1,500 ppm"),
    NO_DATA("데이터 없음", "-");

    private final String label;
    private final String rangeLabel;

    static Co2DistributionLevel from(Integer co2Ppm) {
        if (co2Ppm == null) {
            return NO_DATA;
        }
        if (co2Ppm <= 800) {
            return GOOD;
        }
        if (co2Ppm <= 1_000) {
            return NORMAL;
        }
        if (co2Ppm <= 1_500) {
            return WARNING;
        }
        return BAD;
    }
}
