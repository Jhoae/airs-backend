package com.airs.backend.device.dto;


import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;


@Getter
@AllArgsConstructor
public class DeviceRegisterResponse {

    private String nodeId;
    private Long userId;
    private BigDecimal preferredTemperature;
    private BigDecimal preferredHumidity;
    private String wifiSsid;
    private LocalDateTime createdAt;
}
