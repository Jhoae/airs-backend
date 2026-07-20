package com.airs.backend.sensor.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "influx") // application.yaml의 influx 설정을 필드에 연결한다.
public class InfluxProperties {
    private String url;
    private String token;
    private String org;
    private String bucket;
    private String measurement;
    private String nodeIdTag;
    private String rollupBucket;
    private String rollupMeasurement;
}
