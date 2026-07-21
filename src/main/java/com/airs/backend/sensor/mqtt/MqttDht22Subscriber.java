package com.airs.backend.sensor.mqtt;

import java.nio.charset.StandardCharsets;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.airs.backend.sensor.config.MqttProperties;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.sensor.service.Dht22IngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

// MQTT telemetry를 수신해 센서 적재 서비스로 넘기는 Spring 컴포넌트입니다.
@Component
// 설정과 서비스 의존성을 생성자로 주입합니다.
@RequiredArgsConstructor
// MQTT 수신 기능이 켜진 환경에서만 구독자를 생성합니다.
@ConditionalOnProperty(name = "mqtt.enabled", havingValue = "true", matchIfMissing = true)
public class MqttDht22Subscriber {

    // MQTT 연결과 수신 실패 원인을 기록합니다.
    private static final Logger log = LoggerFactory.getLogger(MqttDht22Subscriber.class);

    // broker 주소와 구독 topic 설정을 사용합니다.
    private final MqttProperties mqttProperties;
    // JSON telemetry를 DTO로 역직렬화합니다.
    private final ObjectMapper objectMapper;
    // 수신한 telemetry의 MySQL·InfluxDB 적재를 담당합니다.
    private final Dht22IngestionService dht22IngestionService;

    // 종료 시 연결을 해제하기 위해 MQTT 클라이언트를 보관합니다.
    private MqttClient mqttClient;

    // 모든 Spring bean이 준비된 뒤 MQTT broker 연결과 topic 구독을 시작합니다.
    @EventListener(ApplicationReadyEvent.class)
    public void start() throws Exception {
        // 연결 전에 필수 MQTT 설정 누락을 즉시 차단합니다.
        validateMqttProperties();

        // 설정된 host·port를 사용해 메모리 기반 MQTT 클라이언트를 생성합니다.
        mqttClient = new MqttClient(
                "tcp://" + mqttProperties.getHost() + ":" + mqttProperties.getPort(),
                MqttClient.generateClientId(),
                new MemoryPersistence()
        );

        // broker 재연결과 세션 정책을 담을 연결 옵션을 생성합니다.
        MqttConnectOptions connectOptions = new MqttConnectOptions();
        // 네트워크 단절 시 Paho가 broker 재연결을 시도하게 합니다.
        connectOptions.setAutomaticReconnect(true);
        // 재연결 후 이전 구독 세션 상태를 재사용하지 않게 합니다.
        connectOptions.setCleanSession(true);

        // 설정된 broker와 실제 MQTT 연결을 맺습니다.
        mqttClient.connect(connectOptions);
        // 운영 로그에서 연결 대상 broker를 확인할 수 있게 남깁니다.
        log.info("MQTT broker에 연결했습니다. host={}, port={}", mqttProperties.getHost(), mqttProperties.getPort());

        // 와일드카드 topic으로 각 노드의 telemetry 메시지를 함께 수신합니다.
        mqttClient.subscribe(mqttProperties.getTopic(), (topic, message) -> {
            try {
                // topic과 payload를 검증·파싱한 뒤 적재 서비스로 전달합니다.
                handleMessage(topic, message);
            } catch (Exception e) {
                // 한 메시지 실패가 전체 MQTT 구독을 멈추지 않게 경고만 남깁니다.
                log.warn("MQTT 메시지 처리에 실패했습니다. topic={}, error={}", topic, e.getMessage());
            }
        });
        // 실제 구독 중인 topic 규칙을 운영 로그에 기록합니다.
        log.info("MQTT topic을 구독했습니다. topic={}", mqttProperties.getTopic());
    }

    // 수신한 MQTT 바이트 payload를 node ID와 telemetry DTO로 변환해 적재합니다.
    private void handleMessage(String topic, MqttMessage message) throws Exception {
        // UTF-8 바이트 배열을 JSON 문자열로 복원합니다.
        String payloadJson = new String(message.getPayload(), StandardCharsets.UTF_8);
        // topic의 세 번째 구간에서 실제 노드 식별자를 추출합니다.
        String nodeId = extractNodeId(topic);

        // JSON 필드 별칭을 반영해 센서 telemetry DTO를 생성합니다.
        Dht22Payload payload = objectMapper.readValue(payloadJson, Dht22Payload.class);
        // 하나의 telemetry를 MySQL 최신 상태와 InfluxDB raw 데이터로 적재합니다.
        dht22IngestionService.ingest(nodeId, payload);
    }

    // 허용된 telemetry topic에서 node ID를 안전하게 분리합니다.
    private String extractNodeId(String topic) {
        // 허용된 topic 형식은 airs/node/node_01/telemetry입니다.
        String[] parts = topic.split("/");

        // 네 구간이 아니면 노드 ID 위치를 신뢰할 수 없어 수신을 거부합니다.
        if (parts.length != 4) {
            throw new IllegalArgumentException("topic 형식이 올바르지 않습니다: " + topic);
        }

        // AIRS telemetry 규칙과 다른 topic은 다른 메시지로 보고 수신을 거부합니다.
        if (!"airs".equals(parts[0]) || !"node".equals(parts[1]) || !"telemetry".equals(parts[3])) {
            throw new IllegalArgumentException("예상한 topic 규칙이 아닙니다: " + topic);
        }

        // 검증된 세 번째 구간을 node ID로 반환합니다.
        return parts[2];
    }

    // 애플리케이션 시작 전에 MQTT 연결에 필요한 설정을 검증합니다.
    private void validateMqttProperties() {
        // host가 비어 있으면 어느 broker에 연결할지 알 수 없습니다.
        if (mqttProperties.getHost() == null || mqttProperties.getHost().isBlank()) {
            throw new IllegalStateException("mqtt.host 설정이 비어 있습니다.");
        }

        // 양수가 아닌 port는 유효한 MQTT 연결 대상이 아닙니다.
        if (mqttProperties.getPort() <= 0) {
            throw new IllegalStateException("mqtt.port 설정이 올바르지 않습니다.");
        }

        // topic이 비어 있으면 어떤 telemetry도 구독할 수 없습니다.
        if (mqttProperties.getTopic() == null || mqttProperties.getTopic().isBlank()) {
            throw new IllegalStateException("mqtt.topic 설정이 비어 있습니다.");
        }
    }

    // Spring 종료 직전에 broker 연결과 클라이언트 자원을 정리합니다.
    @PreDestroy
    public void close() throws Exception {
        // 시작 실패 등으로 클라이언트가 없으면 정리할 자원이 없습니다.
        if (mqttClient != null) {
            // 연결된 경우에만 MQTT disconnect 패킷을 broker에 전송합니다.
            if (mqttClient.isConnected()) {
                mqttClient.disconnect();
                log.info("MQTT broker 연결을 종료했습니다.");
            }
            // 네트워크·메모리 자원을 포함한 MQTT 클라이언트를 닫습니다.
            mqttClient.close();
        }
    }
}
