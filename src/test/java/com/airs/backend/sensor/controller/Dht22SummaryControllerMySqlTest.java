package com.airs.backend.sensor.controller;

import com.airs.backend.device.entity.Device;
import com.airs.backend.device.repository.DeviceRepository;
import com.airs.backend.global.jwt.JwtTokenProvider;
import com.airs.backend.sensor.dto.DailyDht22SummaryResponse;
import com.airs.backend.sensor.influx.InfluxDht22Reader;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class Dht22SummaryControllerMySqlTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private InfluxDht22Reader influxDht22Reader;

    @AfterEach
    void cleanUp() {
        deviceRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        Mockito.reset(influxDht22Reader);
    }

    @Test
    void getDailySummary_should_return_summary_when_user_owns_device() throws Exception {
        Long userId = saveUser("summary-owner@example.com");
        saveDevice(userId, "NODE-SUMMARY-001");
        String accessToken = jwtTokenProvider.generateAccessToken(userId);
        LocalDate date = LocalDate.parse("2026-05-06");

        DailyDht22SummaryResponse summary = new DailyDht22SummaryResponse(
                "NODE-SUMMARY-001",
                date,
                27.3,
                Instant.parse("2026-05-06T12:00:00Z"),
                24.2,
                21.0,
                Instant.parse("2026-05-06T03:00:00Z"),
                60.5,
                Instant.parse("2026-05-06T14:00:00Z"),
                49.1,
                40.2,
                Instant.parse("2026-05-06T05:00:00Z")
        );

        when(influxDht22Reader.readDailySummary("NODE-SUMMARY-001", date)).thenReturn(summary);

        mockMvc.perform(get("/airs/devices/NODE-SUMMARY-001/measurements/summary")
                        .param("date", "2026-05-06")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value("NODE-SUMMARY-001"))
                .andExpect(jsonPath("$.date").value("2026-05-06"))
                .andExpect(jsonPath("$.peakTemperature").value(27.3))
                .andExpect(jsonPath("$.averageHumidity").value(49.1));

        verify(influxDht22Reader).readDailySummary("NODE-SUMMARY-001", date);
    }

    @Test
    void getDailySummary_should_return_unauthorized_when_access_token_is_missing() throws Exception {
        mockMvc.perform(get("/airs/devices/NODE-SUMMARY-001/measurements/summary")
                        .param("date", "2026-05-06"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."));
    }

    @Test
    void getDailySummary_should_return_unauthorized_when_access_token_is_invalid() throws Exception {
        mockMvc.perform(get("/airs/devices/NODE-SUMMARY-001/measurements/summary")
                        .param("date", "2026-05-06")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("유효하지 않은 토큰입니다."));
    }

    @Test
    void getDailySummary_should_return_bad_request_when_date_is_missing() throws Exception {
        Long userId = saveUser("summary-missing-date@example.com");
        String accessToken = jwtTokenProvider.generateAccessToken(userId);

        mockMvc.perform(get("/airs/devices/NODE-SUMMARY-001/measurements/summary")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getDailySummary_should_return_not_found_when_device_does_not_exist() throws Exception {
        Long userId = saveUser("summary-not-found@example.com");
        String accessToken = jwtTokenProvider.generateAccessToken(userId);

        mockMvc.perform(get("/airs/devices/NODE-NOT-FOUND/measurements/summary")
                        .param("date", "2026-05-06")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("기기를 찾을 수 없습니다."));

        verifyNoInteractions(influxDht22Reader);
    }

    @Test
    void getDailySummary_should_return_forbidden_when_device_belongs_to_other_user() throws Exception {
        Long currentUserId = saveUser("summary-current@example.com");
        Long otherUserId = saveUser("summary-other@example.com");
        saveDevice(otherUserId, "NODE-SUMMARY-OTHER");
        String accessToken = jwtTokenProvider.generateAccessToken(currentUserId);

        mockMvc.perform(get("/airs/devices/NODE-SUMMARY-OTHER/measurements/summary")
                        .param("date", "2026-05-06")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("해당 기기에 접근할 수 없습니다."));

        verifyNoInteractions(influxDht22Reader);
    }

    private Long saveUser(String email) {
        return transactionTemplate.execute(status -> {
            User user = userRepository.save(new User(
                    "jaeho",
                    email,
                    "hashed-password",
                    null
            ));
            return user.getUserId();
        });
    }

    private void saveDevice(Long userId, String nodeId) {
        transactionTemplate.executeWithoutResult(status -> {
            User user = userRepository.findById(userId).orElseThrow();
            deviceRepository.save(new Device(
                    nodeId,
                    user,
                    new BigDecimal("23.5"),
                    new BigDecimal("41.0"),
                    "AIRS_WIFI"
            ));
        });
    }
}
