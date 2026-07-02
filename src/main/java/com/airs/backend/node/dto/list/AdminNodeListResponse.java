package com.airs.backend.node.dto.list;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class AdminNodeListResponse {

    private Long campusId;
    private String campusName;
    private Integer radiusMeter;
    private int totalNodeCount;
    private long onlineNodeCount;
    private long weakNodeCount;
    private long offlineNodeCount;
    private long activeAlertCount;
    private List<AdminNodeSummaryResponse> nodes;
}
