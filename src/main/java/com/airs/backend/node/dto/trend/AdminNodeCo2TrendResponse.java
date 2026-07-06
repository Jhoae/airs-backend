package com.airs.backend.node.dto.trend;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

@Getter
@AllArgsConstructor
public class AdminNodeCo2TrendResponse {

    private String nodeId;
    private String period;
    private Instant from;
    private Instant to;
    private String window;
    private List<AdminNodeCo2TrendPointResponse> points;
}
