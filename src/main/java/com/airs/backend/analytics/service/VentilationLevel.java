package com.airs.backend.analytics.service;

enum VentilationLevel {

    GOOD,
    RECOMMENDED,
    NEEDED,
    NO_DATA;

    static VentilationLevel from(Integer co2Ppm) {
        if (co2Ppm == null) {
            return NO_DATA;
        }
        if (co2Ppm <= 1_000) {
            return GOOD;
        }
        if (co2Ppm <= 1_500) {
            return RECOMMENDED;
        }
        return NEEDED;
    }
}
