package com.airs.backend.node.dto.list;

import com.airs.backend.status.entity.ConnectionStatus;
import com.airs.backend.status.entity.OccupancyStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class AdminNodeSummaryResponse {

    private int rank;
    private String nodeId;
    private Long spaceId;
    private String spaceCode;
    private String spaceName;
    private String buildingName;
    private String floorLabel;
    private Integer distanceMeter;
    private ConnectionStatus connectionStatus;
    private BigDecimal temperature;
    private BigDecimal humidity;
    private Integer co2Ppm;
    private String co2Summary;
    private OccupancyStatus occupancyStatus;
    private String occupancySummary;
    private long alertCount;
    private LocalDateTime lastUpdatedAt;
}
