package com.airs.backend.sensor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.airs.backend.sensor.dto.Dht22MeasurementItem;
import com.airs.backend.sensor.dto.Dht22RangeResponse;
import com.airs.backend.sensor.influx.InfluxDht22Reader;

@ExtendWith(MockitoExtension.class)
class AiDht22QueryServiceTest {

    @Mock
    private InfluxDht22Reader influxDht22Reader;

    @InjectMocks
    private AiDht22QueryService aiDht22QueryService;

    @Test
    void getRange_should_wrap_reader_result() {
        Instant from = Instant.parse("2026-05-06T00:00:00Z");
        Instant to = Instant.parse("2026-05-06T01:00:00Z");
        List<Dht22MeasurementItem> measurements = List.of(
                new Dht22MeasurementItem(26.5, 50.3, Instant.parse("2026-05-06T00:00:05Z"))
        );

        when(influxDht22Reader.readRange("node_01", from, to)).thenReturn(measurements);

        Dht22RangeResponse response = aiDht22QueryService.getRange("node_01", from, to);

        assertEquals("node_01", response.getNodeId());
        assertEquals(from, response.getFrom());
        assertEquals(to, response.getTo());
        assertEquals(measurements, response.getMeasurements());
    }

    @Test
    void getRange_should_fail_when_from_is_after_to() {
        Instant from = Instant.parse("2026-05-06T01:00:00Z");
        Instant to = Instant.parse("2026-05-06T00:00:00Z");

        assertThrows(IllegalArgumentException.class,
                () -> aiDht22QueryService.getRange("node_01", from, to));
    }
}
