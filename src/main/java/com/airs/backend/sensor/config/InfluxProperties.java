package com.airs.backend.sensor.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "influx") // application.yaml의 influx 아래 설정을 이 클래스 필드에 묶어라
public class InfluxProperties {
    private String url;
    private String token;
    private String org;
    private String bucket;
    private String measurement;
    private String nodeIdTag;
}
