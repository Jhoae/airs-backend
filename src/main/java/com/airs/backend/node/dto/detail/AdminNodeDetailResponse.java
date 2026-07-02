package com.airs.backend.node.dto.detail;

import com.airs.backend.status.entity.ConnectionStatus;
import com.airs.backend.status.entity.OccupancyStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@AllArgsConstructor
public class AdminNodeDetailResponse {

    private String nodeId;
    private Long spaceId;
    private String spaceCode;
    private String spaceName;
    private String buildingName;
    private String floorLabel;
    private String firmwareVersion;
    private LocalDateTime installedAt;
    private LocalDateTime lastUpdatedAt;
    private ConnectionStatus connectionStatus;
    private Integer wifiRssi;
    private String wifiRssiSummary;
    private BigDecimal temperature;
    private String temperatureSummary;
    private BigDecimal humidity;
    private String humiditySummary;
    private Integer co2Ppm;
    private String co2Summary;
    private Boolean humanDetected;
    private OccupancyStatus occupancyStatus;
    private String occupancySummary;
    private BigDecimal comfortScore;
    private String comfortSummary;
    private List<AdminNodeAlertResponse> alerts;
}
