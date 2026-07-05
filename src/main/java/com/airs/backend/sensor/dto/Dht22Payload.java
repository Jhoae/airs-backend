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

    @JsonAlias("temperature_c")
    private Double temperature;
    @JsonAlias("humidity_pct")
    private Double humidity;
    @JsonAlias("co2_ppm")
    private Integer co2Ppm;
    @JsonAlias("scd41_temperature_c")
    private Double scd41Temperature;
    @JsonAlias("scd41_humidity_pct")
    private Double scd41Humidity;
    private Instant timestamp;

    public Dht22Payload(Double temperature, Double humidity, Instant timestamp) {
        this.temperature = temperature;
        this.humidity = humidity;
        this.timestamp = timestamp;
    }

    public Dht22Payload(Double temperature, Double humidity, Integer co2Ppm, Instant timestamp) {
        this.temperature = temperature;
        this.humidity = humidity;
        this.co2Ppm = co2Ppm;
        this.timestamp = timestamp;
    }
}
