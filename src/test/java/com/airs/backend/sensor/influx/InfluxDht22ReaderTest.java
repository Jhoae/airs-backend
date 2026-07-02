package com.airs.backend.sensor.influx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.airs.backend.sensor.config.InfluxProperties;
import com.airs.backend.sensor.dto.Co2TrendItem;
import com.airs.backend.sensor.dto.DailyDht22SummaryResponse;
import com.airs.backend.sensor.dto.Dht22MeasurementItem;
import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;

@ExtendWith(MockitoExtension.class)
class InfluxDht22ReaderTest {

    @Mock
    private QueryApi queryApi;

    @Mock
    private FluxTable fluxTable;

    @Mock
    private FluxRecord fluxRecord;

    private InfluxDht22Reader influxDht22Reader;

    @BeforeEach
    void setUp() {
        InfluxProperties influxProperties = new InfluxProperties();
        influxProperties.setUrl("http://localhost:8086");
        influxProperties.setToken("test-token");
        influxProperties.setOrg("airs-org");
        influxProperties.setBucket("airs");
        influxProperties.setMeasurement("sensor_data");
        influxProperties.setNodeIdTag("node_id");

        influxDht22Reader = new InfluxDht22Reader(influxProperties);
        ReflectionTestUtils.setField(influxDht22Reader, "queryApi", queryApi);
    }

    @Test
    void readDailySummary_should_return_null_fields_when_no_measurements_exist() {
        when(queryApi.query(org.mockito.ArgumentMatchers.anyString(), eq("airs-org")))
                .thenReturn(List.of());

        DailyDht22SummaryResponse response =
                influxDht22Reader.readDailySummary("node_01", LocalDate.parse("2026-05-06"));

        assertEquals("node_01", response.getNodeId());
        assertEquals(LocalDate.parse("2026-05-06"), response.getDate());
        assertNull(response.getPeakTemperature());
        assertNull(response.getPeakTemperatureTime());
        assertNull(response.getAverageTemperature());
        assertNull(response.getMinTemperature());
        assertNull(response.getMinTemperatureTime());
        assertNull(response.getPeakHumidity());
        assertNull(response.getPeakHumidityTime());
        assertNull(response.getAverageHumidity());
        assertNull(response.getMinHumidity());
        assertNull(response.getMinHumidityTime());
    }

    @Test
    void readRange_should_query_influx_and_map_records() {
        Instant from = Instant.parse("2026-05-06T00:00:00Z");
        Instant to = Instant.parse("2026-05-06T01:00:00Z");
        Instant timestamp = Instant.parse("2026-05-06T00:00:05Z");

        when(fluxTable.getRecords()).thenReturn(List.of(fluxRecord));
        when(fluxRecord.getValueByKey("temperature")).thenReturn(26.5);
        when(fluxRecord.getValueByKey("humidity")).thenReturn(50.3);
        when(fluxRecord.getValueByKey("co2")).thenReturn(842.0);
        when(fluxRecord.getTime()).thenReturn(timestamp);
        when(queryApi.query(org.mockito.ArgumentMatchers.anyString(), eq("airs-org")))
                .thenReturn(List.of(fluxTable));

        List<Dht22MeasurementItem> measurements = influxDht22Reader.readRange("node_01", from, to);

        assertEquals(1, measurements.size());
        assertEquals(26.5, measurements.get(0).getTemperature());
        assertEquals(50.3, measurements.get(0).getHumidity());
        assertEquals(842, measurements.get(0).getCo2Ppm());
        assertEquals(timestamp, measurements.get(0).getTimestamp());

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(queryApi).query(queryCaptor.capture(), eq("airs-org"));
        String query = queryCaptor.getValue();
        assertEquals(true, query.contains("from(bucket: \"airs\")"));
        assertEquals(true, query.contains("r._measurement == \"sensor_data\""));
        assertEquals(true, query.contains("r.node_id == \"node_01\""));
        assertEquals(true, query.contains("r._field == \"temperature\" or r._field == \"humidity\" or r._field == \"co2\""));
    }

    @Test
    void readCo2Trend_should_query_influx_with_aggregate_window() {
        Instant from = Instant.parse("2026-05-06T00:00:00Z");
        Instant to = Instant.parse("2026-05-06T06:00:00Z");
        Instant timestamp = Instant.parse("2026-05-06T00:05:00Z");

        when(fluxTable.getRecords()).thenReturn(List.of(fluxRecord));
        when(fluxRecord.getValue()).thenReturn(842.4);
        when(fluxRecord.getTime()).thenReturn(timestamp);
        when(queryApi.query(org.mockito.ArgumentMatchers.anyString(), eq("airs-org")))
                .thenReturn(List.of(fluxTable));

        List<Co2TrendItem> trend = influxDht22Reader.readCo2Trend("node_01", from, to, "5m");

        assertEquals(1, trend.size());
        assertEquals(timestamp, trend.get(0).getTimestamp());
        assertEquals(842, trend.get(0).getCo2Ppm());

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(queryApi).query(queryCaptor.capture(), eq("airs-org"));
        String query = queryCaptor.getValue();
        assertEquals(true, query.contains("r._field == \"co2\""));
        assertEquals(true, query.contains("aggregateWindow(every: 5m, fn: mean, createEmpty: false)"));
    }

    @Test
    void readRange_should_fail_when_from_is_after_to() {
        Instant from = Instant.parse("2026-05-06T01:00:00Z");
        Instant to = Instant.parse("2026-05-06T00:00:00Z");

        assertThrows(IllegalArgumentException.class,
                () -> influxDht22Reader.readRange("node_01", from, to));
    }
}
