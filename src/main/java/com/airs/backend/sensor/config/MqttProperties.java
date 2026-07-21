package com.airs.backend.sensor.config;


import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
// application.yaml의 mqtt 설정을 필드에 연결한다.
@ConfigurationProperties(prefix = "mqtt")
public class MqttProperties {
    // MQTT broker 호스트 이름 또는 IP 주소다.
    private String host;
    // MQTT broker TCP 포트다.
    private int port;
    // Spring이 구독할 telemetry topic 패턴이다.
    private String topic;
}
