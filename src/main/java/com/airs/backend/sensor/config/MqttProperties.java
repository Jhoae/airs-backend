package com.airs.backend.sensor.config;


import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "mqtt") // application.yaml의 mqtt 아래 설정을 이 클래스 필드에 묶어라
public class MqttProperties {
    private String host;
    private int port;
    private String topic;
}
