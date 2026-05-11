package com.airs.backend.sensor.dto;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Dht22RangeResponse {

    private String nodeId;
    private Instant from;
    private Instant to;
    private List<Dht22MeasurementItem> measurements;
}
