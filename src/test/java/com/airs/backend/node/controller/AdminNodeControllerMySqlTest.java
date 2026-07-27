package com.airs.backend.node.controller;

import com.airs.backend.alert.entity.Alert;
import com.airs.backend.alert.entity.AlertAudience;
import com.airs.backend.alert.entity.AlertSeverity;
import com.airs.backend.alert.entity.AlertType;
import com.airs.backend.alert.repository.AlertRepository;
import com.airs.backend.global.jwt.JwtTokenProvider;
import com.airs.backend.location.entity.Building;
import com.airs.backend.location.entity.Campus;
import com.airs.backend.location.entity.Space;
import com.airs.backend.location.entity.SpaceType;
import com.airs.backend.location.repository.BuildingRepository;
import com.airs.backend.location.repository.CampusRepository;
import com.airs.backend.location.repository.SpaceRepository;
import com.airs.backend.node.entity.AirsNode;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.node.repository.AirsNodeRepository;
import com.airs.backend.node.repository.NodeInstallationRepository;
import com.airs.backend.sensor.dto.Co2TrendItem;
import com.airs.backend.sensor.dto.SensorTrendItem;
import com.airs.backend.sensor.dto.SensorTrendMetric;
import com.airs.backend.sensor.influx.InfluxDht22Reader;
import com.airs.backend.status.entity.ConnectionStatus;
import com.airs.backend.status.entity.NodeStatusSnapshot;
import com.airs.backend.status.entity.OccupancyStatus;
import com.airs.backend.status.entity.SensorStatus;
import com.airs.backend.status.entity.SpaceStatusSnapshot;
import com.airs.backend.status.repository.NodeStatusSnapshotRepository;
import com.airs.backend.status.repository.SpaceStatusSnapshotRepository;
import com.airs.backend.user.entity.CampusAdmin;
import com.airs.backend.user.entity.CampusAdminStatus;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserPreference;
import com.airs.backend.user.entity.UserRole;
import com.airs.backend.user.repository.CampusAdminRepository;
import com.airs.backend.user.repository.UserPreferenceRepository;
import com.airs.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"analytics.cache.enabled=false", "node.sensor-trend.cache.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AdminNodeControllerMySqlTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private CampusRepository campusRepository;

    @Autowired
    private BuildingRepository buildingRepository;

    @Autowired
    private SpaceRepository spaceRepository;

    @Autowired
    private AirsNodeRepository airsNodeRepository;

    @Autowired
    private NodeInstallationRepository nodeInstallationRepository;

    @Autowired
    private NodeStatusSnapshotRepository nodeStatusSnapshotRepository;

    @Autowired
    private SpaceStatusSnapshotRepository spaceStatusSnapshotRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private CampusAdminRepository campusAdminRepository;

    @Autowired
    private UserPreferenceRepository userPreferenceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private InfluxDht22Reader influxDht22Reader;

    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        alertRepository.deleteAllInBatch();
        spaceStatusSnapshotRepository.deleteAllInBatch();
        nodeStatusSnapshotRepository.deleteAllInBatch();
        nodeInstallationRepository.deleteAllInBatch();
        airsNodeRepository.deleteAllInBatch();
        spaceRepository.deleteAllInBatch();
        buildingRepository.deleteAllInBatch();
        campusAdminRepository.deleteAllInBatch();
        userPreferenceRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        campusRepository.deleteAllInBatch();
        org.mockito.Mockito.reset(influxDht22Reader);
    }

    @Test
    void getNodes_should_return_admin_node_list() throws Exception {
        Long adminId = saveNodeListFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);

        mockMvc.perform(get("/airs/admin/nodes")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campusName").value("서강대학교"))
                .andExpect(jsonPath("$.radiusMeter").value(500))
                .andExpect(jsonPath("$.totalNodeCount").value(2))
                .andExpect(jsonPath("$.onlineNodeCount").value(1))
                .andExpect(jsonPath("$.weakNodeCount").value(0))
                .andExpect(jsonPath("$.offlineNodeCount").value(1))
                .andExpect(jsonPath("$.activeAlertCount").value(1))
                .andExpect(jsonPath("$.nodes[0].rank").value(1))
                .andExpect(jsonPath("$.nodes[0].nodeId").value("AIRS-2483"))
                .andExpect(jsonPath("$.nodes[0].spaceCode").value("K301"))
                .andExpect(jsonPath("$.nodes[0].spaceName").value("301호"))
                .andExpect(jsonPath("$.nodes[0].buildingName").value("김대건관"))
                .andExpect(jsonPath("$.nodes[0].floorLabel").value("3층"))
                .andExpect(jsonPath("$.nodes[0].connectionStatus").value("ONLINE"))
                .andExpect(jsonPath("$.nodes[0].temperature").value(24.30))
                .andExpect(jsonPath("$.nodes[0].humidity").value(52.00))
                .andExpect(jsonPath("$.nodes[0].co2Ppm").value(842))
                .andExpect(jsonPath("$.nodes[0].occupancyStatus").value("OCCUPIED"))
                .andExpect(jsonPath("$.nodes[0].alertCount").value(0))
                .andExpect(jsonPath("$.nodes[1].rank").value(2))
                .andExpect(jsonPath("$.nodes[1].nodeId").value("AIRS-904"))
                .andExpect(jsonPath("$.nodes[1].spaceCode").value("R904"))
                .andExpect(jsonPath("$.nodes[1].connectionStatus").value("OFFLINE"))
                .andExpect(jsonPath("$.nodes[1].temperature").doesNotExist())
                .andExpect(jsonPath("$.nodes[1].humidity").doesNotExist())
                .andExpect(jsonPath("$.nodes[1].co2Ppm").doesNotExist())
                .andExpect(jsonPath("$.nodes[1].alertCount").value(1));
    }

    @Test
    void getNode_should_return_admin_node_detail() throws Exception {
        Long adminId = saveNodeListFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);

        mockMvc.perform(get("/airs/admin/nodes/AIRS-2483")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value("AIRS-2483"))
                .andExpect(jsonPath("$.spaceCode").value("K301"))
                .andExpect(jsonPath("$.spaceName").value("301호"))
                .andExpect(jsonPath("$.buildingName").value("김대건관"))
                .andExpect(jsonPath("$.floorLabel").value("3층"))
                .andExpect(jsonPath("$.firmwareVersion").value("v1.2.3"))
                .andExpect(jsonPath("$.connectionStatus").value("ONLINE"))
                .andExpect(jsonPath("$.wifiRssi").value(-45))
                .andExpect(jsonPath("$.wifiRssiSummary").value("강함"))
                .andExpect(jsonPath("$.temperature").value(24.30))
                .andExpect(jsonPath("$.humidity").value(52.00))
                .andExpect(jsonPath("$.co2Ppm").value(842))
                .andExpect(jsonPath("$.humanDetected").value(true))
                .andExpect(jsonPath("$.occupancyStatus").value("OCCUPIED"))
                .andExpect(jsonPath("$.alerts").isArray())
                .andExpect(jsonPath("$.alerts").isEmpty());
    }

    @Test
    void getNode_should_return_alerts_when_node_has_active_alerts() throws Exception {
        Long adminId = saveNodeListFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);

        mockMvc.perform(get("/airs/admin/nodes/AIRS-904")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value("AIRS-904"))
                .andExpect(jsonPath("$.spaceName").value("904호"))
                .andExpect(jsonPath("$.connectionStatus").value("OFFLINE"))
                .andExpect(jsonPath("$.alerts[0].alertType").value("NODE_OFFLINE"))
                .andExpect(jsonPath("$.alerts[0].severity").value("WARNING"))
                .andExpect(jsonPath("$.alerts[0].title").value("노드 오프라인"));
    }

    @Test
    void getNode_should_return_not_found_when_node_is_not_installed() throws Exception {
        Long adminId = saveNodeListFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);

        mockMvc.perform(get("/airs/admin/nodes/AIRS-NOT-FOUND")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("노드를 찾을 수 없습니다."));
    }

    @Test
    void getCo2Trend_should_return_windowed_co2_points() throws Exception {
        Long adminId = saveNodeListFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);
        when(influxDht22Reader.readCo2Trend(eq("AIRS-2483"), any(Instant.class), any(Instant.class), eq("5m")))
                .thenReturn(List.of(
                        new Co2TrendItem(Instant.parse("2026-05-28T03:00:00Z"), 612),
                        new Co2TrendItem(Instant.parse("2026-05-28T03:05:00Z"), 842)
                ));

        mockMvc.perform(get("/airs/admin/nodes/AIRS-2483/co2-trend")
                        .param("hours", "6")
                        .param("window", "5m")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value("AIRS-2483"))
                .andExpect(jsonPath("$.period").doesNotExist())
                .andExpect(jsonPath("$.window").value("5m"))
                .andExpect(jsonPath("$.points[0].co2Ppm").value(612))
                .andExpect(jsonPath("$.points[1].co2Ppm").value(842));

        verify(influxDht22Reader).readCo2Trend(eq("AIRS-2483"), any(Instant.class), any(Instant.class), eq("5m"));
    }

    @Test
    void getSensorTrend_should_return_selected_temperature_points() throws Exception {
        Long adminId = saveNodeListFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);
        when(influxDht22Reader.readSensorTrend(
                eq(SensorTrendMetric.TEMPERATURE),
                eq("AIRS-2483"),
                any(Instant.class),
                any(Instant.class),
                eq("10m")
        )).thenReturn(List.of(
                new SensorTrendItem(Instant.parse("2026-07-21T00:00:00Z"), 24.3),
                new SensorTrendItem(Instant.parse("2026-07-21T00:10:00Z"), 24.8)
        ));

        mockMvc.perform(get("/airs/admin/nodes/AIRS-2483/sensor-trend")
                        .param("metric", "temperature")
                        .param("period", "1d")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value("AIRS-2483"))
                .andExpect(jsonPath("$.metric").value("temperature"))
                .andExpect(jsonPath("$.period").value("1d"))
                .andExpect(jsonPath("$.window").value("10m"))
                .andExpect(jsonPath("$.points[0].value").value(24.3))
                .andExpect(jsonPath("$.points[1].value").value(24.8));

        verify(influxDht22Reader).readSensorTrend(
                eq(SensorTrendMetric.TEMPERATURE),
                eq("AIRS-2483"),
                any(Instant.class),
                any(Instant.class),
                eq("10m")
        );
    }

    @Test
    void getSensorTrend_should_return_selected_occupancy_points() throws Exception {
        Long adminId = saveNodeListFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);
        when(influxDht22Reader.readSensorTrend(
                eq(SensorTrendMetric.OCCUPANCY),
                eq("AIRS-2483"),
                any(Instant.class),
                any(Instant.class),
                eq("10m")
        )).thenReturn(List.of(
                new SensorTrendItem(Instant.parse("2026-07-21T00:00:00Z"), 0.0),
                new SensorTrendItem(Instant.parse("2026-07-21T00:10:00Z"), 1.0)
        ));

        mockMvc.perform(get("/airs/admin/nodes/AIRS-2483/sensor-trend")
                        .param("metric", "occupancy")
                        .param("period", "1d")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric").value("occupancy"))
                .andExpect(jsonPath("$.period").value("1d"))
                .andExpect(jsonPath("$.points[0].value").value(0.0))
                .andExpect(jsonPath("$.points[1].value").value(1.0));

        verify(influxDht22Reader).readSensorTrend(
                eq(SensorTrendMetric.OCCUPANCY),
                eq("AIRS-2483"),
                any(Instant.class),
                any(Instant.class),
                eq("10m")
        );
    }

    @Test
    void getSensorTrend_should_return_selected_comfort_points() throws Exception {
        Long adminId = saveNodeListFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);
        when(influxDht22Reader.readSensorTrend(
                eq(SensorTrendMetric.COMFORT),
                eq("AIRS-2483"),
                any(Instant.class),
                any(Instant.class),
                eq("10m")
        )).thenReturn(List.of(
                new SensorTrendItem(Instant.parse("2026-07-21T00:00:00Z"), 72.0),
                new SensorTrendItem(Instant.parse("2026-07-21T00:10:00Z"), 75.0)
        ));

        mockMvc.perform(get("/airs/admin/nodes/AIRS-2483/sensor-trend")
                        .param("metric", "comfort")
                        .param("period", "1d")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric").value("comfort"))
                .andExpect(jsonPath("$.period").value("1d"))
                .andExpect(jsonPath("$.points[0].value").value(72.0))
                .andExpect(jsonPath("$.points[1].value").value(75.0));

        verify(influxDht22Reader).readSensorTrend(
                eq(SensorTrendMetric.COMFORT),
                eq("AIRS-2483"),
                any(Instant.class),
                any(Instant.class),
                eq("10m")
        );
    }

    @Test
    void getSensorTrend_should_reject_missing_period() throws Exception {
        Long adminId = saveNodeListFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);

        mockMvc.perform(get("/airs/admin/nodes/AIRS-2483/sensor-trend")
                        .param("metric", "humidity")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("period는 1d, 5d, 1mo, 6mo, 1y 중 하나로 필수입니다."));
    }

    @Test
    void getSensorTrend_should_read_hourly_rollup_for_five_day_period() throws Exception {
        Long adminId = saveNodeListFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);
        when(influxDht22Reader.readSensorTrendWithHourlyRollup(
                eq(SensorTrendMetric.HUMIDITY),
                eq("AIRS-2483"),
                any(Instant.class),
                any(Instant.class),
                eq("1h")
        )).thenReturn(List.of(new SensorTrendItem(Instant.parse("2026-07-21T00:00:00Z"), 52.0)));

        mockMvc.perform(get("/airs/admin/nodes/AIRS-2483/sensor-trend")
                        .param("metric", "humidity")
                        .param("period", "5d")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric").value("humidity"))
                .andExpect(jsonPath("$.period").value("5d"))
                .andExpect(jsonPath("$.window").value("1h"));

        verify(influxDht22Reader).readSensorTrendWithHourlyRollup(
                eq(SensorTrendMetric.HUMIDITY),
                eq("AIRS-2483"),
                any(Instant.class),
                any(Instant.class),
                eq("1h")
        );
    }

    @Test
    void getSensorTrend_should_read_six_hour_rollup_for_one_month_period() throws Exception {
        Long adminId = saveNodeListFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);
        when(influxDht22Reader.readSensorTrendWithHourlyRollup(
                eq(SensorTrendMetric.TEMPERATURE),
                eq("AIRS-2483"),
                any(Instant.class),
                any(Instant.class),
                eq("6h")
        )).thenReturn(List.of(new SensorTrendItem(Instant.parse("2026-07-21T00:00:00Z"), 24.3)));

        mockMvc.perform(get("/airs/admin/nodes/AIRS-2483/sensor-trend")
                        .param("metric", "temperature")
                        .param("period", "1mo")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric").value("temperature"))
                .andExpect(jsonPath("$.period").value("1mo"))
                .andExpect(jsonPath("$.window").value("6h"));

        verify(influxDht22Reader).readSensorTrendWithHourlyRollup(
                eq(SensorTrendMetric.TEMPERATURE),
                eq("AIRS-2483"),
                any(Instant.class),
                any(Instant.class),
                eq("6h")
        );
    }

    @Test
    void getSensorTrend_should_read_daily_rollup_for_six_month_and_one_year_periods() throws Exception {
        Long adminId = saveNodeListFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);
        when(influxDht22Reader.readSensorTrendWithDailyRollup(
                eq(SensorTrendMetric.CO2),
                eq("AIRS-2483"),
                any(Instant.class),
                any(Instant.class)
        )).thenReturn(List.of(new SensorTrendItem(Instant.parse("2026-07-21T00:00:00Z"), 842.0)));

        mockMvc.perform(get("/airs/admin/nodes/AIRS-2483/sensor-trend")
                        .param("metric", "co2")
                        .param("period", "6mo")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("6mo"))
                .andExpect(jsonPath("$.window").value("1d"));

        mockMvc.perform(get("/airs/admin/nodes/AIRS-2483/sensor-trend")
                        .param("metric", "co2")
                        .param("period", "1y")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("1y"))
                .andExpect(jsonPath("$.window").value("1d"));

        verify(influxDht22Reader, times(2)).readSensorTrendWithDailyRollup(
                eq(SensorTrendMetric.CO2),
                eq("AIRS-2483"),
                any(Instant.class),
                any(Instant.class)
        );
    }

    @Test
    void getCo2Trend_should_return_period_based_co2_points() throws Exception {
        Long adminId = saveNodeListFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);
        when(influxDht22Reader.readCo2TrendWithHourlyRollup(eq("AIRS-2483"), any(Instant.class), any(Instant.class), eq("1h")))
                .thenReturn(List.of(
                        new Co2TrendItem(Instant.parse("2026-05-28T00:00:00Z"), 900),
                        new Co2TrendItem(Instant.parse("2026-05-28T01:00:00Z"), 980)
                ));

        mockMvc.perform(get("/airs/admin/nodes/AIRS-2483/co2-trend")
                        .param("period", "5d")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value("AIRS-2483"))
                .andExpect(jsonPath("$.period").value("5d"))
                .andExpect(jsonPath("$.window").value("1h"))
                .andExpect(jsonPath("$.points[0].co2Ppm").value(900))
                .andExpect(jsonPath("$.points[1].co2Ppm").value(980));

        verify(influxDht22Reader).readCo2TrendWithHourlyRollup(
                eq("AIRS-2483"),
                any(Instant.class),
                any(Instant.class),
                eq("1h")
        );
    }

    @Test
    void getCo2Trend_should_read_hourly_rollup_for_one_month_period() throws Exception {
        Long adminId = saveNodeListFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);
        when(influxDht22Reader.readCo2TrendWithHourlyRollup(eq("AIRS-2483"), any(Instant.class), any(Instant.class), eq("6h")))
                .thenReturn(List.of(new Co2TrendItem(Instant.parse("2026-05-28T06:00:00Z"), 842)));

        mockMvc.perform(get("/airs/admin/nodes/AIRS-2483/co2-trend")
                        .param("period", "1mo")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("1mo"))
                .andExpect(jsonPath("$.window").value("6h"))
                .andExpect(jsonPath("$.points[0].co2Ppm").value(842));

        verify(influxDht22Reader).readCo2TrendWithHourlyRollup(
                eq("AIRS-2483"),
                any(Instant.class),
                any(Instant.class),
                eq("6h")
        );
    }

    @Test
    void getCo2Trend_should_read_daily_rollup_for_six_month_period() throws Exception {
        Long adminId = saveNodeListFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);
        when(influxDht22Reader.readCo2TrendWithDailyRollup(eq("AIRS-2483"), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(
                        new Co2TrendItem(Instant.parse("2026-05-28T00:00:00Z"), 900),
                        new Co2TrendItem(Instant.parse("2026-05-29T00:00:00Z"), 980)
                ));

        mockMvc.perform(get("/airs/admin/nodes/AIRS-2483/co2-trend")
                        .param("period", "6mo")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value("AIRS-2483"))
                .andExpect(jsonPath("$.period").value("6mo"))
                .andExpect(jsonPath("$.window").value("1d"))
                .andExpect(jsonPath("$.points[0].co2Ppm").value(900))
                .andExpect(jsonPath("$.points[1].co2Ppm").value(980));

        verify(influxDht22Reader).readCo2TrendWithDailyRollup(
                eq("AIRS-2483"),
                any(Instant.class),
                any(Instant.class)
        );
    }

    @Test
    void getCo2Trend_should_return_bad_request_when_window_is_invalid() throws Exception {
        Long adminId = saveNodeListFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);

        mockMvc.perform(get("/airs/admin/nodes/AIRS-2483/co2-trend")
                        .param("window", "5 minutes")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("window 형식이 올바르지 않습니다."));
    }

    @Test
    void getCo2Trend_should_return_bad_request_when_period_is_invalid() throws Exception {
        Long adminId = saveNodeListFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);

        mockMvc.perform(get("/airs/admin/nodes/AIRS-2483/co2-trend")
                        .param("period", "2w")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("period는 1d, 5d, 1mo, 6mo, 1y 중 하나여야 합니다."));
    }

    @Test
    void getCo2Summary_should_count_only_spaces_with_active_node_installations() throws Exception {
        Long adminId = saveCo2AnalyticsFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);

        mockMvc.perform(get("/airs/admin/analytics/co2/summary")
                        .param("date", "2026-07-06")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campusName").value("서강대학교"))
                .andExpect(jsonPath("$.date").value("2026-07-06"))
                .andExpect(jsonPath("$.totalSpaceCount").value(3))
                .andExpect(jsonPath("$.averageCo2Ppm").value(1203))
                .andExpect(jsonPath("$.ventilationSummary.goodCount").value(1))
                .andExpect(jsonPath("$.ventilationSummary.recommendedCount").value(1))
                .andExpect(jsonPath("$.ventilationSummary.neededCount").value(1))
                .andExpect(jsonPath("$.ventilationSummary.noDataCount").value(0));

        verify(influxDht22Reader, never()).readAverageCo2Trend(anyList(), any(Instant.class), any(Instant.class), any());
    }

    @Test
    void getCo2Distribution_should_count_only_spaces_with_active_node_installations() throws Exception {
        Long adminId = saveCo2AnalyticsFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);

        mockMvc.perform(get("/airs/admin/analytics/co2/distribution")
                        .param("date", "2026-07-06")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campusName").value("서강대학교"))
                .andExpect(jsonPath("$.totalSpaceCount").value(3))
                .andExpect(jsonPath("$.averageCo2Ppm").value(1203))
                .andExpect(jsonPath("$.distribution[0].status").value("GOOD"))
                .andExpect(jsonPath("$.distribution[0].count").value(1))
                .andExpect(jsonPath("$.distribution[0].unit").value("SPACE"))
                .andExpect(jsonPath("$.distribution[0].totalCount").value(3))
                .andExpect(jsonPath("$.distribution[2].status").value("WARNING"))
                .andExpect(jsonPath("$.distribution[2].count").value(1))
                .andExpect(jsonPath("$.distribution[3].status").value("BAD"))
                .andExpect(jsonPath("$.distribution[3].count").value(1))
                .andExpect(jsonPath("$.distribution[4].status").value("NO_DATA"))
                .andExpect(jsonPath("$.distribution[4].count").value(0));

        verify(influxDht22Reader, never()).readAverageCo2Trend(anyList(), any(Instant.class), any(Instant.class), any());
    }

    @Test
    void getCo2Trend_should_return_today_and_yesterday_trend_lists() throws Exception {
        Long adminId = saveCo2AnalyticsFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);
        when(influxDht22Reader.readAverageCo2TrendWithHourlyRollup(anyList(), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(
                        new Co2TrendItem(Instant.parse("2026-07-05T15:00:00Z"), 700),
                        new Co2TrendItem(Instant.parse("2026-07-06T00:00:00Z"), 842),
                        new Co2TrendItem(Instant.parse("2026-07-06T01:00:00Z"), 901),
                        new Co2TrendItem(Instant.parse("2026-07-06T15:00:00Z"), 920)
                )
                );

        mockMvc.perform(get("/airs/admin/analytics/co2/trend")
                        .param("date", "2026-07-06")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campusName").value("서강대학교"))
                .andExpect(jsonPath("$.date").value("2026-07-06"))
                .andExpect(jsonPath("$.todayTrend[0].timestamp").value("2026-07-06T00:00:00Z"))
                .andExpect(jsonPath("$.todayTrend[0].co2Ppm").value(842))
                .andExpect(jsonPath("$.todayTrend[1].co2Ppm").value(901))
                .andExpect(jsonPath("$.todayTrend[2].timestamp").value("2026-07-06T15:00:00Z"))
                .andExpect(jsonPath("$.todayTrend[2].co2Ppm").value(920))
                .andExpect(jsonPath("$.yesterdayTrend[0].timestamp").value("2026-07-05T15:00:00Z"))
                .andExpect(jsonPath("$.yesterdayTrend[0].co2Ppm").value(700));

        verify(influxDht22Reader).readAverageCo2TrendWithHourlyRollup(
                anyList(),
                eq(Instant.parse("2026-07-04T15:00:00Z")),
                eq(Instant.parse("2026-07-06T15:00:00Z"))
        );
    }

    @Test
    void getCo2TopSpaces_should_return_top_spaces_without_influx_trend_query() throws Exception {
        Long adminId = saveCo2AnalyticsFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);

        mockMvc.perform(get("/airs/admin/analytics/co2/top-spaces")
                        .param("date", "2026-07-06")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campusName").value("서강대학교"))
                .andExpect(jsonPath("$.topSpaces[0].spaceCode").value("D501"))
                .andExpect(jsonPath("$.topSpaces[0].co2Ppm").value(1582))
                .andExpect(jsonPath("$.topSpaces[0].co2Status").value("BAD"))
                .andExpect(jsonPath("$.topSpaces[1].spaceCode").value("R904"))
                .andExpect(jsonPath("$.topSpaces[2].spaceCode").value("K301"));

        verify(influxDht22Reader, never()).readAverageCo2Trend(anyList(), any(Instant.class), any(Instant.class), any());
    }

    @Test
    void getAnalyticsOverviewMetrics_should_return_metrics_without_influx_trend_query() throws Exception {
        Long adminId = saveCo2AnalyticsFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);

        mockMvc.perform(get("/airs/admin/analytics/overview/metrics")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ventilationRecommendedSpaceCount").value(1))
                .andExpect(jsonPath("$.ventilationNeededSpaceCount").value(1))
                .andExpect(jsonPath("$.totalNodeCount").value(3))
                .andExpect(jsonPath("$.unknownNodeCount").value(3));

        verify(influxDht22Reader, never()).readAverageCo2Trend(anyList(), any(Instant.class), any(Instant.class), any());
    }

    @Test
    void getAnalyticsOverviewStatusDistributions_should_count_only_spaces_with_active_node_installations() throws Exception {
        Long adminId = saveCo2AnalyticsFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);

        mockMvc.perform(get("/airs/admin/analytics/overview/status-distributions")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.co2[0].status").value("GOOD"))
                .andExpect(jsonPath("$.co2[0].count").value(1))
                .andExpect(jsonPath("$.co2[0].unit").value("SPACE"))
                .andExpect(jsonPath("$.co2[0].totalCount").value(3))
                .andExpect(jsonPath("$.co2[2].status").value("WARNING"))
                .andExpect(jsonPath("$.co2[2].count").value(1))
                .andExpect(jsonPath("$.co2[3].status").value("BAD"))
                .andExpect(jsonPath("$.co2[3].count").value(1))
                .andExpect(jsonPath("$.occupancy[0].status").value("OCCUPIED"))
                .andExpect(jsonPath("$.occupancy[0].count").value(2))
                .andExpect(jsonPath("$.occupancy[0].unit").value("SPACE"))
                .andExpect(jsonPath("$.occupancy[0].totalCount").value(3))
                .andExpect(jsonPath("$.occupancy[1].status").value("UNOCCUPIED"))
                .andExpect(jsonPath("$.occupancy[1].count").value(1))
                .andExpect(jsonPath("$.occupancy[3].status").value("NO_DATA"))
                .andExpect(jsonPath("$.occupancy[3].count").value(0))
                .andExpect(jsonPath("$.connection[3].status").value("UNKNOWN"))
                .andExpect(jsonPath("$.connection[3].count").value(3))
                .andExpect(jsonPath("$.connection[3].unit").value("NODE"))
                .andExpect(jsonPath("$.connection[3].totalCount").value(3))
                .andExpect(jsonPath("$.wifi[3].status").value("NO_DATA"))
                .andExpect(jsonPath("$.wifi[3].count").value(3))
                .andExpect(jsonPath("$.wifi[3].unit").value("NODE"))
                .andExpect(jsonPath("$.wifi[3].totalCount").value(3));

        verify(influxDht22Reader, never()).readAverageCo2Trend(anyList(), any(Instant.class), any(Instant.class), any());
    }

    @Test
    void cors_should_allow_any_web_origin_during_development() throws Exception {
        mockMvc.perform(options("/airs/auth/login")
                        .header("Origin", "https://airs.bibnear.cloud")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://airs.bibnear.cloud"));
    }

    @Test
    void cors_should_allow_another_web_origin_during_development() throws Exception {
        mockMvc.perform(options("/airs/auth/login")
                        .header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://untrusted.example"));
    }

    @Test
    void getNodes_should_sort_alert_nodes_first_when_sort_is_alert() throws Exception {
        Long adminId = saveNodeListFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);

        mockMvc.perform(get("/airs/admin/nodes")
                        .param("sort", "alert")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[0].nodeId").value("AIRS-904"))
                .andExpect(jsonPath("$.nodes[0].alertCount").value(1))
                .andExpect(jsonPath("$.nodes[1].nodeId").value("AIRS-2483"))
                .andExpect(jsonPath("$.nodes[1].alertCount").value(0));
    }

    @Test
    void getNodes_should_return_forbidden_when_user_is_not_admin() throws Exception {
        Long userId = saveUser();
        String accessToken = jwtTokenProvider.generateAccessToken(userId);

        mockMvc.perform(get("/airs/admin/nodes")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."));
    }

    @Test
    void getNodes_should_return_bad_request_when_sort_is_invalid() throws Exception {
        Long adminId = saveNodeListFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(adminId);

        mockMvc.perform(get("/airs/admin/nodes")
                        .param("sort", "unknown")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("지원하지 않는 노드 정렬 기준입니다."));
    }

    @Test
    void registerNode_should_create_installation_with_mqtt_node_id() throws Exception {
        RegistrationFixture fixture = saveRegistrationFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(fixture.adminId());
        String requestJson = """
                {
                  "nodeId": "node_01",
                  "spaceId": %d,
                  "hardwareVersion": "ESP32-C3",
                  "firmwareVersion": "v1.0.0",
                  "wifiRssi": -45
                }
                """.formatted(fixture.k301SpaceId());

        mockMvc.perform(post("/airs/admin/nodes/installations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nodeId").value("node_01"))
                .andExpect(jsonPath("$.spaceId").value(fixture.k301SpaceId()))
                .andExpect(jsonPath("$.spaceCode").value("K301"))
                .andExpect(jsonPath("$.spaceName").value("301호"))
                .andExpect(jsonPath("$.buildingName").value("김대건관"))
                .andExpect(jsonPath("$.created").value(true));

        transactionTemplate.executeWithoutResult(status -> {
            assertTrue(airsNodeRepository.existsById("node_01"));

            NodeInstallation installation = nodeInstallationRepository.findByNode_IdAndActiveTrue("node_01")
                    .orElseThrow();
            assertEquals(fixture.k301SpaceId(), installation.getSpace().getId());

            NodeStatusSnapshot nodeStatus = nodeStatusSnapshotRepository.findByNode_Id("node_01")
                    .orElseThrow();
            assertEquals(ConnectionStatus.UNKNOWN, nodeStatus.getConnectionStatus());
            assertEquals(SensorStatus.NO_DATA, nodeStatus.getSensorStatus());
            assertEquals(-45, nodeStatus.getWifiRssi());

            SpaceStatusSnapshot spaceStatus = spaceStatusSnapshotRepository.findBySpace_Id(fixture.k301SpaceId())
                    .orElseThrow();
            assertEquals("node_01", spaceStatus.getRepresentativeNode().getId());
        });
    }

    @Test
    void registerNode_should_return_existing_installation_when_same_node_and_space_are_registered_again() throws Exception {
        RegistrationFixture fixture = saveRegistrationFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(fixture.adminId());
        String firstRequestJson = """
                {
                  "nodeId": "node_01",
                  "spaceId": %d,
                  "hardwareVersion": "ESP32-C3",
                  "firmwareVersion": "v1.0.0",
                  "wifiRssi": -45
                }
                """.formatted(fixture.k301SpaceId());
        String retryRequestJson = """
                {
                  "nodeId": "node_01",
                  "spaceId": %d,
                  "hardwareVersion": "ESP32-S3",
                  "firmwareVersion": "v2.0.0",
                  "wifiRssi": -30
                }
                """.formatted(fixture.k301SpaceId());

        mockMvc.perform(post("/airs/admin/nodes/installations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(APPLICATION_JSON)
                        .content(firstRequestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true));

        mockMvc.perform(post("/airs/admin/nodes/installations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(APPLICATION_JSON)
                        .content(retryRequestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value("node_01"))
                .andExpect(jsonPath("$.spaceId").value(fixture.k301SpaceId()))
                .andExpect(jsonPath("$.created").value(false));

        transactionTemplate.executeWithoutResult(status -> {
            AirsNode node = airsNodeRepository.findById("node_01").orElseThrow();
            assertEquals("ESP32-C3", node.getHardwareVersion());
            assertEquals("v1.0.0", node.getFirmwareVersion());

            NodeStatusSnapshot nodeStatus = nodeStatusSnapshotRepository.findByNode_Id("node_01")
                    .orElseThrow();
            assertEquals(-45, nodeStatus.getWifiRssi());
        });
    }

    @Test
    void registerNode_should_return_conflict_when_node_is_active_in_another_space() throws Exception {
        RegistrationFixture fixture = saveRegistrationFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(fixture.adminId());
        String firstRequestJson = """
                {
                  "nodeId": "node_01",
                  "spaceId": %d
                }
                """.formatted(fixture.k301SpaceId());
        String conflictRequestJson = """
                {
                  "nodeId": "node_01",
                  "spaceId": %d
                }
                """.formatted(fixture.r904SpaceId());

        mockMvc.perform(post("/airs/admin/nodes/installations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(APPLICATION_JSON)
                        .content(firstRequestJson))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/airs/admin/nodes/installations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(APPLICATION_JSON)
                        .content(conflictRequestJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 다른 공간에 등록된 노드입니다."));
    }

    @Test
    void createCampus_should_create_campus_for_admin() throws Exception {
        RegistrationFixture fixture = saveRegistrationFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(fixture.adminId());
        String requestJson = """
                {
                  "name": "테스트대학교",
                  "latitude": null,
                  "longitude": null,
                  "radiusMeter": 500
                }
                """;

        mockMvc.perform(post("/airs/admin/campuses")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("테스트대학교"));
    }

    @Test
    void createCampus_should_return_forbidden_for_user() throws Exception {
        Long userId = saveUser();
        String accessToken = jwtTokenProvider.generateAccessToken(userId);
        String requestJson = """
                {
                  "name": "테스트대학교"
                }
                """;

        mockMvc.perform(post("/airs/admin/campuses")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."));
    }

    @Test
    void getBuildings_should_return_buildings_for_admin_campus() throws Exception {
        RegistrationFixture fixture = saveRegistrationFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(fixture.adminId());

        mockMvc.perform(get("/airs/admin/campuses/{campusId}/buildings", fixture.campusId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].buildingId").value(fixture.kimDaegeonBuildingId()))
                .andExpect(jsonPath("$[0].name").value("김대건관"))
                .andExpect(jsonPath("$[1].buildingId").value(fixture.loyolaBuildingId()))
                .andExpect(jsonPath("$[1].name").value("로욜라도서관"));
    }

    @Test
    void createBuilding_should_create_building_for_admin_campus() throws Exception {
        RegistrationFixture fixture = saveRegistrationFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(fixture.adminId());
        String requestJson = """
                {
                  "name": "다산관"
                }
                """;

        mockMvc.perform(post("/airs/admin/campuses/{campusId}/buildings", fixture.campusId())
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("다산관"));

        transactionTemplate.executeWithoutResult(status -> {
            List<Building> buildings = buildingRepository.findAllByCampus_IdAndDeletedAtIsNullOrderByNameAsc(
                    fixture.campusId()
            );
            assertEquals(3, buildings.size());
        });
    }

    @Test
    void createBuilding_should_return_conflict_when_name_already_exists_in_same_campus() throws Exception {
        RegistrationFixture fixture = saveRegistrationFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(fixture.adminId());
        String requestJson = """
                {
                  "name": "김대건관"
                }
                """;

        mockMvc.perform(post("/airs/admin/campuses/{campusId}/buildings", fixture.campusId())
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 사용 중인 건물 이름입니다."));
    }

    @Test
    void getSpaces_should_return_spaces_for_selected_building() throws Exception {
        RegistrationFixture fixture = saveRegistrationFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(fixture.adminId());

        mockMvc.perform(get("/airs/admin/buildings/{buildingId}/spaces", fixture.kimDaegeonBuildingId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].spaceId").value(fixture.k301SpaceId()))
                .andExpect(jsonPath("$[0].code").value("K301"))
                .andExpect(jsonPath("$[0].name").value("301호"))
                .andExpect(jsonPath("$[0].floorLabel").value("3층"))
                .andExpect(jsonPath("$[0].spaceType").value("CLASSROOM"));
    }

    @Test
    void createSpace_should_create_space_for_selected_building() throws Exception {
        RegistrationFixture fixture = saveRegistrationFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(fixture.adminId());
        String requestJson = """
                {
                  "code": "K302",
                  "name": "302호",
                  "floorLabel": "3층",
                  "spaceType": "CLASSROOM",
                  "latitude": null,
                  "longitude": null
                }
                """;

        mockMvc.perform(post("/airs/admin/buildings/{buildingId}/spaces", fixture.kimDaegeonBuildingId())
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("K302"))
                .andExpect(jsonPath("$.name").value("302호"))
                .andExpect(jsonPath("$.floorLabel").value("3층"))
                .andExpect(jsonPath("$.spaceType").value("CLASSROOM"));

        transactionTemplate.executeWithoutResult(status -> {
            List<Space> spaces = spaceRepository.findAllByBuilding_IdAndDeletedAtIsNullOrderByCodeAsc(
                    fixture.kimDaegeonBuildingId()
            );
            assertEquals(2, spaces.size());
            assertEquals("K302", spaces.get(1).getCode());
            assertNull(spaces.get(1).getLatitude());
            assertNull(spaces.get(1).getLongitude());
        });
    }

    @Test
    void createSpace_should_return_conflict_when_code_already_exists_in_same_campus() throws Exception {
        RegistrationFixture fixture = saveRegistrationFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(fixture.adminId());
        String requestJson = """
                {
                  "code": "K301",
                  "name": "다른 301호"
                }
                """;

        mockMvc.perform(post("/airs/admin/buildings/{buildingId}/spaces", fixture.kimDaegeonBuildingId())
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 사용 중인 공간 코드입니다."));
    }

    @Test
    void createSpace_should_return_forbidden_when_building_belongs_to_another_campus() throws Exception {
        RegistrationFixture fixture = saveRegistrationFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(fixture.adminId());
        Long otherBuildingId = transactionTemplate.execute(status -> {
            Campus otherCampus = campusRepository.save(new Campus("다른 학교", null, null, null));
            Building otherBuilding = buildingRepository.save(new Building(otherCampus, "다른 건물"));
            return otherBuilding.getId();
        });
        String requestJson = """
                {
                  "code": "X101",
                  "name": "101호"
                }
                """;

        mockMvc.perform(post("/airs/admin/buildings/{buildingId}/spaces", otherBuildingId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("해당 캠퍼스 위치 정보를 조회할 수 없습니다."));
    }

    @Test
    void deleteNode_should_deactivate_installation_and_allow_registration_again() throws Exception {
        RegistrationFixture fixture = saveRegistrationFixture();
        String accessToken = jwtTokenProvider.generateAccessToken(fixture.adminId());
        String firstRequestJson = """
                {
                  "nodeId": "node_01",
                  "spaceId": %d,
                  "hardwareVersion": "ESP32-C3",
                  "firmwareVersion": "v1.0.0",
                  "wifiRssi": -45
                }
                """.formatted(fixture.k301SpaceId());
        String secondRequestJson = """
                {
                  "nodeId": "node_01",
                  "spaceId": %d,
                  "hardwareVersion": "ESP32-C3",
                  "firmwareVersion": "v1.0.1",
                  "wifiRssi": -50
                }
                """.formatted(fixture.r904SpaceId());

        mockMvc.perform(post("/airs/admin/nodes/installations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(APPLICATION_JSON)
                        .content(firstRequestJson))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/airs/admin/nodes/node_01")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        transactionTemplate.executeWithoutResult(status -> {
            assertFalse(nodeInstallationRepository.existsByNode_IdAndActiveTrue("node_01"));

            NodeStatusSnapshot nodeStatus = nodeStatusSnapshotRepository.findByNode_Id("node_01")
                    .orElseThrow();
            assertEquals(ConnectionStatus.OFFLINE, nodeStatus.getConnectionStatus());
            assertEquals(SensorStatus.NO_DATA, nodeStatus.getSensorStatus());

            SpaceStatusSnapshot k301Status = spaceStatusSnapshotRepository.findBySpace_Id(fixture.k301SpaceId())
                    .orElseThrow();
            assertNull(k301Status.getRepresentativeNode());
        });

        mockMvc.perform(post("/airs/admin/nodes/installations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(APPLICATION_JSON)
                        .content(secondRequestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nodeId").value("node_01"))
                .andExpect(jsonPath("$.spaceId").value(fixture.r904SpaceId()))
                .andExpect(jsonPath("$.created").value(true));

        transactionTemplate.executeWithoutResult(status -> {
            NodeInstallation installation = nodeInstallationRepository.findByNode_IdAndActiveTrue("node_01")
                    .orElseThrow();
            assertEquals(fixture.r904SpaceId(), installation.getSpace().getId());

            AirsNode node = airsNodeRepository.findById("node_01").orElseThrow();
            assertEquals("v1.0.1", node.getFirmwareVersion());

            NodeStatusSnapshot nodeStatus = nodeStatusSnapshotRepository.findByNode_Id("node_01")
                    .orElseThrow();
            assertEquals(ConnectionStatus.UNKNOWN, nodeStatus.getConnectionStatus());
            assertEquals(SensorStatus.NO_DATA, nodeStatus.getSensorStatus());
            assertEquals(-50, nodeStatus.getWifiRssi());
        });
    }

    private Long saveNodeListFixture() {
        return transactionTemplate.execute(status -> {
            Campus campus = campusRepository.save(new Campus("서강대학교", null, null, 500));
            User admin = saveUser(campus, "admin@example.com", UserRole.ADMIN);
            campusAdminRepository.save(new CampusAdmin(campus, admin, CampusAdminStatus.APPROVED));

            Building kimDaegeon = buildingRepository.save(new Building(campus, "김대건관"));
            Building loyola = buildingRepository.save(new Building(campus, "로욜라도서관"));

            Space k301 = spaceRepository.save(new Space(
                    campus,
                    kimDaegeon,
                    "K301",
                    "301호",
                    "3층",
                    SpaceType.CLASSROOM,
                    null,
                    null
            ));
            Space r904 = spaceRepository.save(new Space(
                    campus,
                    loyola,
                    "R904",
                    "904호",
                    "9층",
                    SpaceType.READING_ROOM,
                    null,
                    null
            ));

            LocalDateTime now = LocalDateTime.parse("2026-05-28T09:41:00");
            AirsNode k301Node = airsNodeRepository.save(new AirsNode("AIRS-2483", null, "v1.2.3"));
            AirsNode r904Node = airsNodeRepository.save(new AirsNode("AIRS-904", null, "v1.2.3"));

            nodeInstallationRepository.save(new NodeInstallation(k301Node, k301, admin, now.minusDays(10)));
            nodeInstallationRepository.save(new NodeInstallation(r904Node, r904, admin, now.minusDays(8)));

            nodeStatusSnapshotRepository.save(new NodeStatusSnapshot(
                    k301Node,
                    ConnectionStatus.ONLINE,
                    SensorStatus.NORMAL,
                    -45,
                    true,
                    now.minusMinutes(1),
                    now.minusMinutes(1)
            ));
            nodeStatusSnapshotRepository.save(new NodeStatusSnapshot(
                    r904Node,
                    ConnectionStatus.OFFLINE,
                    SensorStatus.NO_DATA,
                    null,
                    null,
                    now.minusMinutes(38),
                    null
            ));
            spaceStatusSnapshotRepository.save(new SpaceStatusSnapshot(
                    k301,
                    k301Node,
                    new BigDecimal("24.30"),
                    new BigDecimal("52.00"),
                    842,
                    true,
                    OccupancyStatus.OCCUPIED,
                    null,
                    now.minusMinutes(1)
            ));
            alertRepository.save(new Alert(
                    campus,
                    r904,
                    r904Node,
                    AlertType.NODE_OFFLINE,
                    AlertSeverity.WARNING,
                    AlertAudience.ADMIN,
                    "노드 오프라인",
                    "R904 노드가 오프라인입니다.",
                    "connection",
                    null,
                    null,
                    "AIRS-904:OFFLINE",
                    now.minusMinutes(38)
            ));

            return admin.getUserId();
        });
    }

    private Long saveUser() {
        return transactionTemplate.execute(status -> {
            Campus campus = campusRepository.save(new Campus("일반 사용자 캠퍼스", null, null, null));
            User user = saveUser(campus, "user@example.com", UserRole.USER);
            return user.getUserId();
        });
    }

    private Long saveCo2AnalyticsFixture() {
        return transactionTemplate.execute(status -> {
            Campus campus = campusRepository.save(new Campus("서강대학교", null, null, 500));
            User admin = saveUser(campus, "analytics-admin@example.com", UserRole.ADMIN);
            campusAdminRepository.save(new CampusAdmin(campus, admin, CampusAdminStatus.APPROVED));

            Building kimDaegeon = buildingRepository.save(new Building(campus, "김대건관"));
            Building loyola = buildingRepository.save(new Building(campus, "로욜라도서관"));
            Building dasan = buildingRepository.save(new Building(campus, "다산관"));
            Building berchmans = buildingRepository.save(new Building(campus, "베르크만스관"));

            Space k301 = spaceRepository.save(new Space(
                    campus,
                    kimDaegeon,
                    "K301",
                    "301호",
                    "3층",
                    SpaceType.CLASSROOM,
                    null,
                    null
            ));
            Space r904 = spaceRepository.save(new Space(
                    campus,
                    loyola,
                    "R904",
                    "904호",
                    "9층",
                    SpaceType.READING_ROOM,
                    null,
                    null
            ));
            Space d501 = spaceRepository.save(new Space(
                    campus,
                    dasan,
                    "D501",
                    "501호",
                    "5층",
                    SpaceType.CLASSROOM,
                    null,
                    null
            ));
            spaceRepository.save(new Space(
                    campus,
                    berchmans,
                    "B102",
                    "102호",
                    "1층",
                    SpaceType.CLASSROOM,
                    null,
                    null
            ));

            LocalDateTime now = LocalDateTime.parse("2026-07-06T09:41:00");
            AirsNode k301Node = airsNodeRepository.save(new AirsNode("node_01", "ESP32-C3", "v1.0.0"));
            AirsNode r904Node = airsNodeRepository.save(new AirsNode("node_02", "ESP32-C3", "v1.0.0"));
            AirsNode d501Node = airsNodeRepository.save(new AirsNode("node_03", "ESP32-C3", "v1.0.0"));

            nodeInstallationRepository.save(new NodeInstallation(k301Node, k301, admin, now.minusDays(1)));
            nodeInstallationRepository.save(new NodeInstallation(r904Node, r904, admin, now.minusDays(1)));
            nodeInstallationRepository.save(new NodeInstallation(d501Node, d501, admin, now.minusDays(1)));

            spaceStatusSnapshotRepository.save(new SpaceStatusSnapshot(
                    k301,
                    k301Node,
                    new BigDecimal("24.30"),
                    new BigDecimal("52.00"),
                    780,
                    true,
                    OccupancyStatus.OCCUPIED,
                    null,
                    now.minusMinutes(1)
            ));
            spaceStatusSnapshotRepository.save(new SpaceStatusSnapshot(
                    r904,
                    r904Node,
                    new BigDecimal("25.10"),
                    new BigDecimal("48.00"),
                    1248,
                    true,
                    OccupancyStatus.OCCUPIED,
                    null,
                    now.minusMinutes(2)
            ));
            spaceStatusSnapshotRepository.save(new SpaceStatusSnapshot(
                    d501,
                    d501Node,
                    new BigDecimal("25.90"),
                    new BigDecimal("60.00"),
                    1582,
                    false,
                    OccupancyStatus.UNOCCUPIED,
                    null,
                    now.minusMinutes(3)
            ));

            return admin.getUserId();
        });
    }

    private User saveUser(Campus campus, String email, UserRole role) {
        User user = userRepository.save(new User(
                campus,
                "jaeho",
                email,
                "hashed-password",
                "01012345678",
                role
        ));

        UserPreference userPreference = new UserPreference(null, null, null);
        userPreference.assignUser(user);
        userPreferenceRepository.save(userPreference);

        return user;
    }

    private RegistrationFixture saveRegistrationFixture() {
        return transactionTemplate.execute(status -> {
            Campus campus = campusRepository.save(new Campus("서강대학교", null, null, 500));
            User admin = saveUser(campus, "registration-admin@example.com", UserRole.ADMIN);
            campusAdminRepository.save(new CampusAdmin(campus, admin, CampusAdminStatus.APPROVED));

            Building kimDaegeon = buildingRepository.save(new Building(campus, "김대건관"));
            Building loyola = buildingRepository.save(new Building(campus, "로욜라도서관"));

            Space k301 = spaceRepository.save(new Space(
                    campus,
                    kimDaegeon,
                    "K301",
                    "301호",
                    "3층",
                    SpaceType.CLASSROOM,
                    null,
                    null
            ));
            Space r904 = spaceRepository.save(new Space(
                    campus,
                    loyola,
                    "R904",
                    "904호",
                    "9층",
                    SpaceType.READING_ROOM,
                    null,
                    null
            ));

            return new RegistrationFixture(
                    admin.getUserId(),
                    campus.getCampusId(),
                    kimDaegeon.getId(),
                    loyola.getId(),
                    k301.getId(),
                    r904.getId()
            );
        });
    }

    private record RegistrationFixture(
            Long adminId,
            Long campusId,
            Long kimDaegeonBuildingId,
            Long loyolaBuildingId,
            Long k301SpaceId,
            Long r904SpaceId
    ) {
    }
}
