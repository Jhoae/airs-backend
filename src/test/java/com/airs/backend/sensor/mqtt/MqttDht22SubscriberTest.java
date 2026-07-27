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
import com.airs.backend.sensor.service.TelemetryIngestionDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class MqttDht22SubscriberTest {

    @Mock
    private TelemetryIngestionDispatcher telemetryIngestionDispatcher;

    private MqttDht22Subscriber mqttDht22Subscriber;

    @BeforeEach
    void setUp() {
        MqttProperties mqttProperties = new MqttProperties();
        mqttProperties.setHost("localhost");
        mqttProperties.setPort(1883);
        mqttProperties.setTopic("airs/node/+/telemetry");

        mqttDht22Subscriber = new MqttDht22Subscriber(
                mqttProperties,
                new ObjectMapper().findAndRegisterModules(),
                telemetryIngestionDispatcher
        );
    }

    @Test
    void handleMessage_should_parse_payload_and_pass_it_to_service() throws Exception {
        String json = """
                {
                  "temperature_c": 26.5,
                  "humidity_pct": 50.3,
                  "co2_ppm": 842,
                  "boot_id": "boot-node-01",
                  "sequence_no": 42,
                  "timestamp": "2026-04-10T10:00:00Z"
                }
                """;
        MqttMessage message = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));

        ReflectionTestUtils.invokeMethod(
                mqttDht22Subscriber,
                "handleMessage",
                "airs/node/node_01/telemetry",
                message
        );

        ArgumentCaptor<Dht22Payload> payloadCaptor = ArgumentCaptor.forClass(Dht22Payload.class);
        verify(telemetryIngestionDispatcher).dispatch(eq("node_01"), payloadCaptor.capture());

        Dht22Payload payload = payloadCaptor.getValue();
        assertEquals(26.5, payload.getTemperature());
        assertEquals(50.3, payload.getHumidity());
        assertEquals(842, payload.getCo2Ppm());
        assertEquals("boot-node-01", payload.getBootId());
        assertEquals(42L, payload.getSequenceNo());
        assertEquals(Instant.parse("2026-04-10T10:00:00Z"), payload.getTimestamp());
    }

    @Test
    void handleMessage_should_ignore_unknown_fields() throws Exception {
        String json = """
                {
                  "temperature_c": 26.5,
                  "humidity_pct": 50.3,
                  "co2_ppm": 956,
                  "abc": "ignored"
                }
                """;
        MqttMessage message = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));

        ReflectionTestUtils.invokeMethod(
                mqttDht22Subscriber,
                "handleMessage",
                "airs/node/node_01/telemetry",
                message
        );

        ArgumentCaptor<Dht22Payload> payloadCaptor = ArgumentCaptor.forClass(Dht22Payload.class);
        verify(telemetryIngestionDispatcher).dispatch(eq("node_01"), payloadCaptor.capture());

        Dht22Payload payload = payloadCaptor.getValue();
        assertEquals(26.5, payload.getTemperature());
        assertEquals(50.3, payload.getHumidity());
        assertEquals(956, payload.getCo2Ppm());
    }

    @Test
    void handleMessage_should_accept_telemetry_payload_with_co2_ppm() throws Exception {
        String json = """
                {
                  "node_id": "node_01",
                  "temperature_c": 21.1,
                  "humidity_pct": 63.1,
                  "co2_ppm": 1900,
                  "scd41_temperature_c": 20.6,
                  "scd41_humidity_pct": 61.9,
                  "pir_detected": 1,
                  "mmwave_detected": 0,
                  "wifi_signal_dbm": -58,
                  "sensor_status": {
                    "dht22": "OK",
                    "scd41": "OK"
                  }
                }
                """;
        MqttMessage message = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));

        ReflectionTestUtils.invokeMethod(
                mqttDht22Subscriber,
                "handleMessage",
                "airs/node/node_01/telemetry",
                message
        );

        ArgumentCaptor<Dht22Payload> payloadCaptor = ArgumentCaptor.forClass(Dht22Payload.class);
        verify(telemetryIngestionDispatcher).dispatch(eq("node_01"), payloadCaptor.capture());

        Dht22Payload payload = payloadCaptor.getValue();
        assertEquals(21.1, payload.getTemperature());
        assertEquals(63.1, payload.getHumidity());
        assertEquals(1900, payload.getCo2Ppm());
        assertEquals(20.6, payload.getScd41Temperature());
        assertEquals(61.9, payload.getScd41Humidity());
        assertEquals(1, payload.getPirDetected());
        assertEquals(0, payload.getMmwaveDetected());
        assertEquals(-58, payload.getWifiSignalDbm());
        assertEquals("OK", payload.getDht22Status());
        assertEquals("OK", payload.getScd41Status());
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
