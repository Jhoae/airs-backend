package com.airs.backend.sensor.mqtt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.airs.backend.sensor.config.MqttProperties;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.sensor.service.Dht22IngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class MqttDht22SubscriberTest {

    @Mock
    private Dht22IngestionService dht22IngestionService;

    private MqttDht22Subscriber mqttDht22Subscriber;

    @BeforeEach
    void setUp() {
        MqttProperties mqttProperties = new MqttProperties();
        mqttProperties.setHost("localhost");
        mqttProperties.setPort(1883);
        mqttProperties.setTopic("airs/node/+/dht22");

        mqttDht22Subscriber = new MqttDht22Subscriber(
                mqttProperties,
                new ObjectMapper().findAndRegisterModules(),
                dht22IngestionService
        );
    }

    @Test
    void handleMessage_should_parse_payload_and_pass_it_to_service() throws Exception {
        String json = """
                {
                  "temperature": 26.5,
                  "humidity": 50.3,
                  "timestamp": "2026-04-10T10:00:00Z"
                }
                """;
        MqttMessage message = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));

        ReflectionTestUtils.invokeMethod(
                mqttDht22Subscriber,
                "handleMessage",
                "airs/node/node_01/dht22",
                message
        );

        ArgumentCaptor<Dht22Payload> payloadCaptor = ArgumentCaptor.forClass(Dht22Payload.class);
        verify(dht22IngestionService).ingest(eq("node_01"), payloadCaptor.capture());

        Dht22Payload payload = payloadCaptor.getValue();
        assertEquals(26.5, payload.getTemperature());
        assertEquals(50.3, payload.getHumidity());
        assertEquals(Instant.parse("2026-04-10T10:00:00Z"), payload.getTimestamp());
    }

    @Test
    void handleMessage_should_ignore_unknown_fields() throws Exception {
        String json = """
                {
                  "temperature": 26.5,
                  "humidity": 50.3,
                  "abc": "ignored"
                }
                """;
        MqttMessage message = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));

        ReflectionTestUtils.invokeMethod(
                mqttDht22Subscriber,
                "handleMessage",
                "airs/node/node_01/dht22",
                message
        );

        ArgumentCaptor<Dht22Payload> payloadCaptor = ArgumentCaptor.forClass(Dht22Payload.class);
        verify(dht22IngestionService).ingest(eq("node_01"), payloadCaptor.capture());

        Dht22Payload payload = payloadCaptor.getValue();
        assertEquals(26.5, payload.getTemperature());
        assertEquals(50.3, payload.getHumidity());
    }

    @Test
    void extractNodeId_should_fail_when_topic_format_is_invalid() {
        assertThrows(IllegalArgumentException.class,
                () -> ReflectionTestUtils.invokeMethod(
                        mqttDht22Subscriber,
                        "extractNodeId",
                        "airs/node_only"
                ));
    }
}
