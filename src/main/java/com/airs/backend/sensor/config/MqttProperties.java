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
    // 재시작 뒤 broker의 미확인 QoS 메시지를 같은 세션으로 다시 받기 위한 고정 ID다.
    private String clientId = "airs-backend-telemetry";
    // 명시적으로 구독할 QoS다. telemetry 신뢰성 경로는 QoS 1을 기본으로 사용한다.
    private int subscriptionQos = 1;
}
