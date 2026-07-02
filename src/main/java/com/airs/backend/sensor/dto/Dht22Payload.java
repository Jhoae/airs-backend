package com.airs.backend.sensor.dto;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Dht22Payload {

    private Double temperature;
    private Double humidity;
    @JsonAlias({"co2", "co2_ppm"})
    private Integer co2Ppm;
    private Instant timestamp;

    public Dht22Payload(Double temperature, Double humidity, Instant timestamp) {
        this.temperature = temperature;
        this.humidity = humidity;
        this.timestamp = timestamp;
    }
}
