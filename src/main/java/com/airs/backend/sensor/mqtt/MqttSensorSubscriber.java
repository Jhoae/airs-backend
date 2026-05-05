package com.airs.backend.sensor.mqtt;

import java.nio.charset.StandardCharsets;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.airs.backend.sensor.config.MqttProperties;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.sensor.service.SensorDataIngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

@Component
@RequiredArgsConstructor
public class MqttSensorSubscriber {

    private static final Logger log = LoggerFactory.getLogger(MqttSensorSubscriber.class);

    private final MqttProperties mqttProperties;
    private final ObjectMapper objectMapper;
    private final SensorDataIngestionService sensorDataIngestionService;

    private MqttClient mqttClient;

    // Spring이 준비 완료(설정 bean 준비 완료, service bean 준비 완료, 전체 앱 컨텍스트 준비 완료)되면 안전하게 sub
    @EventListener(ApplicationReadyEvent.class)
    public void start() throws Exception {
        validateMqttProperties();

        mqttClient = new MqttClient(
                "tcp://" + mqttProperties.getHost() + ":" + mqttProperties.getPort(),
                MqttClient.generateClientId()
        );

        MqttConnectOptions connectOptions = new MqttConnectOptions();
        connectOptions.setAutomaticReconnect(true);
        connectOptions.setCleanSession(true);

        mqttClient.connect(connectOptions);
        log.info("MQTT broker에 연결했습니다. host={}, port={}", mqttProperties.getHost(), mqttProperties.getPort());

        mqttClient.subscribe(mqttProperties.getTopic(), (topic, message) -> {
            try {
                handleMessage(topic, message);
            } catch (Exception e) {
                log.warn("MQTT 메시지 처리에 실패했습니다. topic={}, error={}", topic, e.getMessage());
            }
        });
        log.info("MQTT topic을 구독했습니다. topic={}", mqttProperties.getTopic());
    }

    private void handleMessage(String topic, MqttMessage message) throws Exception {
        String payloadJson = new String(message.getPayload(), StandardCharsets.UTF_8);
        String nodeId = extractNodeId(topic);

        Dht22Payload payload = objectMapper.readValue(payloadJson, Dht22Payload.class);
        sensorDataIngestionService.ingest(nodeId, payload);
    }

    private String extractNodeId(String topic) {
        // EX: airs/node/node_01/dht22
        String[] parts = topic.split("/");

        if (parts.length != 4) {
            throw new IllegalArgumentException("topic 형식이 올바르지 않습니다: " + topic);
        }

        if (!"airs".equals(parts[0]) || !"node".equals(parts[1]) || !"dht22".equals(parts[3])) {
            throw new IllegalArgumentException("예상한 topic 규칙이 아닙니다: " + topic);
        }

        return parts[2];
    }

    private void validateMqttProperties() {
        if (mqttProperties.getHost() == null || mqttProperties.getHost().isBlank()) {
            throw new IllegalStateException("mqtt.host 설정이 비어 있습니다.");
        }

        if (mqttProperties.getPort() <= 0) {
            throw new IllegalStateException("mqtt.port 설정이 올바르지 않습니다.");
        }

        if (mqttProperties.getTopic() == null || mqttProperties.getTopic().isBlank()) {
            throw new IllegalStateException("mqtt.topic 설정이 비어 있습니다.");
        }
    }

    @PreDestroy
    public void close() throws Exception {
        if (mqttClient != null) {
            if (mqttClient.isConnected()) {
                mqttClient.disconnect();
                log.info("MQTT broker 연결을 종료했습니다.");
            }
            mqttClient.close();
        }
    }
}
