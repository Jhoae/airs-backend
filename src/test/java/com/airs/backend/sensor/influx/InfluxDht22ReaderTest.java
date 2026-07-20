package com.airs.backend.sensor.influx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import com.airs.backend.sensor.dto.AiSensorTrendData;
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
        influxProperties.setRollupBucket("airs_rollup");
        influxProperties.setRollupMeasurement("sensor_rollup_1h");

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
        assertEquals(true, query.contains("r._field == \"co2_ppm\""));
        assertEquals(true, query.contains("aggregateWindow(every: 5m, fn: mean, createEmpty: false)"));
    }

    @Test
    void readAverageCo2Trend_should_query_average_trend_for_multiple_nodes() {
        Instant from = Instant.parse("2026-05-06T00:00:00Z");
        Instant to = Instant.parse("2026-05-06T23:59:59Z");
        Instant timestamp = Instant.parse("2026-05-06T01:00:00Z");

        when(fluxTable.getRecords()).thenReturn(List.of(fluxRecord));
        when(fluxRecord.getValue()).thenReturn(901.6);
        when(fluxRecord.getTime()).thenReturn(timestamp);
        when(queryApi.query(org.mockito.ArgumentMatchers.anyString(), eq("airs-org")))
                .thenReturn(List.of(fluxTable));

        List<Co2TrendItem> trend = influxDht22Reader.readAverageCo2Trend(
                List.of("node_01", "node_02"),
                from,
                to,
                "1h"
        );

        assertEquals(1, trend.size());
        assertEquals(timestamp, trend.get(0).getTimestamp());
        assertEquals(902, trend.get(0).getCo2Ppm());

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(queryApi).query(queryCaptor.capture(), eq("airs-org"));
        String query = queryCaptor.getValue();
        assertEquals(true, query.contains("contains(value: r.node_id, set: [\"node_01\", \"node_02\"])"));
        assertEquals(true, query.contains("aggregateWindow(every: 1h, fn: mean, createEmpty: false)"));
        assertEquals(true, query.contains("group(columns: [\"_time\"])"));
        assertEquals(true, query.contains("mean(column: \"_value\")"));
    }

    @Test
    void readAverageCo2TrendWithHourlyRollup_should_merge_rollup_with_latest_raw_data() {
        Instant from = Instant.parse("2026-07-10T00:00:00Z");
        Instant to = Instant.parse("2026-07-10T04:00:00Z");

        FluxTable rollupTable = mock(FluxTable.class);
        FluxRecord rollupRecord = mock(FluxRecord.class);
        when(rollupRecord.getValue()).thenReturn(901.4);
        when(rollupRecord.getTime()).thenReturn(Instant.parse("2026-07-10T02:00:00Z"));
        when(rollupTable.getRecords()).thenReturn(List.of(rollupRecord));

        FluxTable rawTailTable = mock(FluxTable.class);
        FluxRecord rawTailRecord = mock(FluxRecord.class);
        when(rawTailRecord.getValue()).thenReturn(920.6);
        when(rawTailRecord.getTime()).thenReturn(Instant.parse("2026-07-10T03:00:00Z"));
        when(rawTailTable.getRecords()).thenReturn(List.of(rawTailRecord));

        when(queryApi.query(anyString(), eq("airs-org")))
                .thenReturn(List.of(rollupTable))
                .thenReturn(List.of())
                .thenReturn(List.of(rawTailTable));

        List<Co2TrendItem> trend = influxDht22Reader.readAverageCo2TrendWithHourlyRollup(
                List.of("node_01", "node_02"),
                from,
                to
        );

        assertEquals(2, trend.size());
        assertEquals(Instant.parse("2026-07-10T02:00:00Z"), trend.get(0).getTimestamp());
        assertEquals(901, trend.get(0).getCo2Ppm());
        assertEquals(Instant.parse("2026-07-10T03:00:00Z"), trend.get(1).getTimestamp());
        assertEquals(921, trend.get(1).getCo2Ppm());

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(queryApi, times(3)).query(queryCaptor.capture(), eq("airs-org"));
        List<String> queries = queryCaptor.getAllValues();
        assertEquals(true, queries.get(0).contains("from(bucket: \"airs_rollup\")"));
        assertEquals(true, queries.get(0).contains("r._measurement == \"sensor_rollup_1h\""));
        assertEquals(true, queries.get(0).contains("r._field == \"co2_mean\""));
        assertEquals(true, queries.get(2).contains("from(bucket: \"airs\")"));
        assertEquals(true, queries.get(2).contains("aggregateWindow(every: 1h, fn: mean, createEmpty: false)"));
    }

    @Test
    void readAverageCo2TrendWithHourlyRollup_should_fall_back_to_raw_when_rollup_query_fails() {
        Instant from = Instant.parse("2026-07-10T00:00:00Z");
        Instant to = Instant.parse("2026-07-10T01:00:00Z");
        FluxTable rawTable = mock(FluxTable.class);
        FluxRecord rawRecord = mock(FluxRecord.class);
        when(rawRecord.getValue()).thenReturn(842.0);
        when(rawRecord.getTime()).thenReturn(Instant.parse("2026-07-10T01:00:00Z"));
        when(rawTable.getRecords()).thenReturn(List.of(rawRecord));

        when(queryApi.query(anyString(), eq("airs-org")))
                .thenThrow(new IllegalStateException("bucket not found"))
                .thenReturn(List.of(rawTable));

        List<Co2TrendItem> trend = influxDht22Reader.readAverageCo2TrendWithHourlyRollup(
                List.of("node_01"),
                from,
                to
        );

        assertEquals(1, trend.size());
        assertEquals(842, trend.get(0).getCo2Ppm());

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(queryApi, times(2)).query(queryCaptor.capture(), eq("airs-org"));
        assertEquals(true, queryCaptor.getAllValues().get(0).contains("from(bucket: \"airs_rollup\")"));
        assertEquals(true, queryCaptor.getAllValues().get(1).contains("from(bucket: \"airs\")"));
    }

    @Test
    void readCo2TrendWithDailyRollup_should_merge_completed_days_with_latest_raw_data() {
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-04T12:00:00Z");

        FluxTable rollupTable = mock(FluxTable.class);
        FluxRecord rollupRecord = mock(FluxRecord.class);
        when(rollupRecord.getValue()).thenReturn(808.4);
        when(rollupRecord.getTime()).thenReturn(Instant.parse("2026-07-03T00:00:00Z"));
        when(rollupTable.getRecords()).thenReturn(List.of(rollupRecord));

        FluxTable rawTailTable = mock(FluxTable.class);
        FluxRecord rawTailRecord = mock(FluxRecord.class);
        when(rawTailRecord.getValue()).thenReturn(901.6);
        when(rawTailRecord.getTime()).thenReturn(Instant.parse("2026-07-04T12:00:00Z"));
        when(rawTailTable.getRecords()).thenReturn(List.of(rawTailRecord));

        when(queryApi.query(anyString(), eq("airs-org")))
                .thenReturn(List.of(rollupTable))
                .thenReturn(List.of())
                .thenReturn(List.of(rawTailTable));

        List<Co2TrendItem> trend = influxDht22Reader.readCo2TrendWithDailyRollup("node_01", from, to);

        assertEquals(2, trend.size());
        assertEquals(Instant.parse("2026-07-03T00:00:00Z"), trend.get(0).getTimestamp());
        assertEquals(808, trend.get(0).getCo2Ppm());
        assertEquals(Instant.parse("2026-07-04T12:00:00Z"), trend.get(1).getTimestamp());
        assertEquals(902, trend.get(1).getCo2Ppm());

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(queryApi, times(3)).query(queryCaptor.capture(), eq("airs-org"));
        List<String> queries = queryCaptor.getAllValues();
        assertEquals(true, queries.get(0).contains("from(bucket: \"airs_rollup\")"));
        assertEquals(true, queries.get(0).contains("r._measurement == \"sensor_rollup_1d\""));
        assertEquals(true, queries.get(0).contains("r._field == \"co2_mean\""));
        assertEquals(true, queries.get(2).contains("from(bucket: \"airs\")"));
        assertEquals(true, queries.get(2).contains("aggregateWindow(every: 1d, fn: mean, createEmpty: false)"));
    }

    @Test
    void readAiSensorTrend_should_query_recent_measurements_and_calculate_ai_trend_values() {
        Instant to = Instant.parse("2026-07-09T01:30:00Z");

        FluxTable measurementTable = mock(FluxTable.class);
        FluxRecord firstRecord = measurementRecord("2026-07-09T01:00:00Z", 25.0, 50.0, 900.0);
        FluxRecord tenMinutesAgoRecord = measurementRecord("2026-07-09T01:20:00Z", 24.5, 51.0, 970.0);
        FluxRecord overThresholdRecord = measurementRecord("2026-07-09T01:25:00Z", 24.0, 52.0, 1100.0);
        FluxRecord latestRecord = measurementRecord("2026-07-09T01:30:00Z", 23.8, 52.5, 1120.0);
        when(measurementTable.getRecords()).thenReturn(List.of(
                firstRecord,
                tenMinutesAgoRecord,
                overThresholdRecord,
                latestRecord
        ));

        FluxTable occupancyTable = mock(FluxTable.class);
        FluxRecord minutesSinceMotionRecord = mock(FluxRecord.class);
        when(minutesSinceMotionRecord.getValue()).thenReturn(12.4);
        when(occupancyTable.getRecords()).thenReturn(List.of(minutesSinceMotionRecord));

        when(queryApi.query(anyString(), eq("airs-org")))
                .thenReturn(List.of(measurementTable))
                .thenReturn(List.of(occupancyTable));

        AiSensorTrendData trend = influxDht22Reader.readAiSensorTrend("node_01", to);

        assertEquals(23.8, trend.getLatestMeasurement().getTemperature());
        assertEquals(52.5, trend.getLatestMeasurement().getHumidity());
        assertEquals(1120, trend.getLatestMeasurement().getCo2Ppm());
        assertEquals(150.0, trend.getCo2Rate10m());
        assertEquals(5, trend.getCo2Over1000Minutes());
        assertEquals(-1.2, trend.getTempRate30m(), 0.0001);
        assertEquals(12, trend.getNoOccupancyMinutes());

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(queryApi, times(2)).query(queryCaptor.capture(), eq("airs-org"));
        List<String> queries = queryCaptor.getAllValues();
        assertEquals(true, queries.get(0).contains("r._field == \"temperature_c\" or r._field == \"humidity_pct\" or r._field == \"co2_ppm\""));
        assertEquals(true, queries.get(1).contains("r._field == \"minutes_since_motion\""));
        assertEquals(true, queries.get(1).contains("|> last()"));
    }

    private FluxRecord measurementRecord(
            String timestamp,
            Double temperature,
            Double humidity,
            Double co2Ppm
    ) {
        FluxRecord record = mock(FluxRecord.class);
        when(record.getValueByKey("temperature_c")).thenReturn(temperature);
        when(record.getValueByKey("humidity_pct")).thenReturn(humidity);
        when(record.getValueByKey("co2_ppm")).thenReturn(co2Ppm);
        when(record.getTime()).thenReturn(Instant.parse(timestamp));
        return record;
    }
}
