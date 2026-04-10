package com.airs.backend.device.dto;


import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class DeviceSummaryResponse {
    private final String nodeId;
    private final BigDecimal preferredTemperature;
    private final BigDecimal preferredHumidity;
    private final String wifiSsid;
    private final LocalDateTime createdAt;
}
