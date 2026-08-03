package com.airs.backend.sensor.mqtt;

import java.nio.charset.StandardCharsets;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.airs.backend.sensor.config.MqttProperties;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.sensor.service.TelemetryIngestionDispatcher;
import com.airs.backend.sensor.service.TelemetryIngestionCommand;
import com.airs.backend.sensor.service.TelemetryPayloadValidator;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;
import java.time.Instant;

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
    // 노드별 순서를 보존하며 telemetry 적재 작업을 분배합니다.
    private final TelemetryIngestionDispatcher telemetryIngestionDispatcher;
    // callback에서 retry해도 바뀌지 않는 입력 오류를 worker 전달 전에 차단합니다.
    private final TelemetryPayloadValidator telemetryPayloadValidator;
    // retry해도 달라지지 않는 입력 거부를 원인별로 집계합니다.
    private final MeterRegistry meterRegistry;

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
                mqttProperties.getClientId(),
                new MemoryPersistence()
        );

        // callback 반환과 무관하게 MySQL transaction 완료 시점에 직접 ACK합니다.
        mqttClient.setManualAcks(true);
        // persistent session의 미확인 메시지는 connect 직후 재전달될 수 있으므로 연결 전에 callback을 등록합니다.
        mqttClient.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                if (reconnect) {
                    log.info("MQTT broker에 재연결했습니다. serverUri={}", serverURI);
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                log.warn("MQTT broker 연결이 끊어졌습니다. error={}",
                        cause == null ? "unknown" : cause.getMessage());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                try {
                    handleMessage(topic, message);
                } catch (Exception exception) {
                    // transaction까지 가지 못한 예상하지 못한 오류는 ACK하지 않아 재전달 가능성을 남깁니다.
                    log.warn("MQTT 메시지 처리에 실패했습니다. topic={}, error={}", topic, exception.getMessage());
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // 이 클라이언트는 telemetry를 publish하지 않으므로 처리할 outbound delivery가 없습니다.
            }
        });

        // broker 재연결과 세션 정책을 담을 연결 옵션을 생성합니다.
        MqttConnectOptions connectOptions = new MqttConnectOptions();
        // 네트워크 단절 시 Paho가 broker 재연결을 시도하게 합니다.
        connectOptions.setAutomaticReconnect(true);
        // 고정 clientId의 기존 구독 세션과 미확인 QoS 메시지를 재사용하게 합니다.
        connectOptions.setCleanSession(false);

        // 설정된 broker와 실제 MQTT 연결을 맺습니다.
        mqttClient.connect(connectOptions);
        // 운영 로그에서 연결 대상 broker를 확인할 수 있게 남깁니다.
        log.info("MQTT broker에 연결했습니다. host={}, port={}", mqttProperties.getHost(), mqttProperties.getPort());

        // 와일드카드 topic으로 각 노드의 telemetry 메시지를 함께 수신합니다.
        mqttClient.subscribe(mqttProperties.getTopic(), mqttProperties.getSubscriptionQos());
        // 실제 구독 중인 topic 규칙을 운영 로그에 기록합니다.
        log.info("MQTT topic을 구독했습니다. topic={}", mqttProperties.getTopic());
    }

    // 수신한 MQTT 바이트 payload를 node ID와 telemetry DTO로 변환해 적재합니다.
    private void handleMessage(String topic, MqttMessage message) throws Exception {
        Instant receivedAt = Instant.now();
        if (message.isDuplicate()) {
            log.info("MQTT broker가 미확인 QoS 메시지를 재전달했습니다. topic={}, messageId={}, qos={}",
                    topic, message.getId(), message.getQos());
        }
        // UTF-8 바이트 배열을 JSON 문자열로 복원합니다.
        String payloadJson = new String(message.getPayload(), StandardCharsets.UTF_8);
        try {
            // topic의 세 번째 구간에서 실제 노드 식별자를 추출합니다.
            String nodeId = extractNodeId(topic);
            // JSON 필드 별칭을 반영해 센서 telemetry DTO를 생성합니다.
            Dht22Payload payload = objectMapper.readValue(payloadJson, Dht22Payload.class);
            telemetryPayloadValidator.validateAndStamp(nodeId, payload, receivedAt);
            // ACK 책임까지 worker에 넘겨 MySQL transaction commit 뒤에만 broker에 완료를 알립니다.
            telemetryIngestionDispatcher.dispatch(new TelemetryIngestionCommand(
                    nodeId,
                    payload,
                    receivedAt,
                    message.getId(),
                    message.getQos(),
                    () -> completeAcknowledgment(message)
            ));
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            // 다시 받아도 바뀌지 않는 poison message는 로그를 남기고 ACK해 무한 재전달을 막습니다.
            meterRegistry.counter(
                    "airs.telemetry.ingestion.rejected",
                    "reason",
                    rejectionReason(exception)
            ).increment();
            log.warn("재시도할 수 없는 MQTT telemetry를 거부했습니다. topic={}, messageId={}, error={}",
                    topic, message.getId(), exception.getMessage());
            completeAcknowledgment(message);
        }
    }

    private String rejectionReason(Exception exception) {
        if (exception instanceof com.fasterxml.jackson.core.JsonProcessingException) {
            return "malformed_json";
        }
        return exception.getMessage() != null && exception.getMessage().contains("topic")
                ? "invalid_topic"
                : "invalid_payload";
    }

    private void completeAcknowledgment(MqttMessage message) throws Exception {
        if (mqttClient != null) {
            mqttClient.messageArrivedComplete(message.getId(), message.getQos());
        }
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
        if (mqttProperties.getClientId() == null || mqttProperties.getClientId().isBlank()) {
            throw new IllegalStateException("mqtt.client-id 설정이 비어 있습니다.");
        }
        if (mqttProperties.getSubscriptionQos() < 0 || mqttProperties.getSubscriptionQos() > 2) {
            throw new IllegalStateException("mqtt.subscription-qos 설정이 올바르지 않습니다.");
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
