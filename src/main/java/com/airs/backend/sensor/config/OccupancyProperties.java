package com.airs.backend.sensor.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "occupancy")
public class OccupancyProperties {

    private double staleAfterMinutes = 10.0;
    private boolean influxWriteEnabled = false;
}
