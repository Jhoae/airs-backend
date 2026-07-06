package com.airs.backend.node.dto.trend;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;

public enum AdminNodeCo2TrendPeriod {
    ONE_DAY("1d", 1, "10m"),
    FIVE_DAYS("5d", 5, "1h"),
    ONE_MONTH("1mo", 30, "6h"),
    SIX_MONTHS("6mo", 180, "1d"),
    ONE_YEAR("1y", 365, "1d");

    private final String value;
    private final int days;
    private final String window;

    AdminNodeCo2TrendPeriod(String value, int days, String window) {
        this.value = value;
        this.days = days;
        this.window = window;
    }

    public String getValue() {
        return value;
    }

    public int getDays() {
        return days;
    }

    public String getWindow() {
        return window;
    }

    public static AdminNodeCo2TrendPeriod from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return Arrays.stream(values())
                .filter(period -> period.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "period는 1d, 5d, 1mo, 6mo, 1y 중 하나여야 합니다."
                ));
    }
}
