package com.airs.backend.sensor.controller;

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
import com.airs.backend.sensor.dto.DailyDht22SummaryResponse;
import com.airs.backend.sensor.influx.InfluxDht22Reader;
import com.airs.backend.user.entity.CampusAdmin;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserRole;
import com.airs.backend.user.repository.CampusAdminRepository;
import com.airs.backend.user.repository.UserPreferenceRepository;
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
    private CampusAdminRepository campusAdminRepository;

    @Autowired
    private UserPreferenceRepository userPreferenceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private InfluxDht22Reader influxDht22Reader;

    @AfterEach
    void cleanUp() {
        nodeInstallationRepository.deleteAllInBatch();
        airsNodeRepository.deleteAllInBatch();
        spaceRepository.deleteAllInBatch();
        buildingRepository.deleteAllInBatch();
        campusAdminRepository.deleteAllInBatch();
        userPreferenceRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        campusRepository.deleteAllInBatch();
        Mockito.reset(influxDht22Reader);
    }

    @Test
    void getDailySummary_should_return_summary_when_admin_can_access_node() throws Exception {
        Long userId = saveApprovedAdmin("summary-owner@example.com", "summary-owner-campus");
        saveNodeInstallation(userId, "NODE-SUMMARY-001");
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

        mockMvc.perform(get("/airs/nodes/NODE-SUMMARY-001/measurements/summary")
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
        mockMvc.perform(get("/airs/nodes/NODE-SUMMARY-001/measurements/summary")
                        .param("date", "2026-05-06"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."));
    }

    @Test
    void getDailySummary_should_return_unauthorized_when_access_token_is_invalid() throws Exception {
        mockMvc.perform(get("/airs/nodes/NODE-SUMMARY-001/measurements/summary")
                        .param("date", "2026-05-06")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("유효하지 않은 토큰입니다."));
    }

    @Test
    void getDailySummary_should_return_bad_request_when_date_is_missing() throws Exception {
        Long userId = saveApprovedAdmin("summary-missing-date@example.com", "summary-missing-date-campus");
        String accessToken = jwtTokenProvider.generateAccessToken(userId);

        mockMvc.perform(get("/airs/nodes/NODE-SUMMARY-001/measurements/summary")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getDailySummary_should_return_not_found_when_node_installation_does_not_exist() throws Exception {
        Long userId = saveApprovedAdmin("summary-not-found@example.com", "summary-not-found-campus");
        String accessToken = jwtTokenProvider.generateAccessToken(userId);

        mockMvc.perform(get("/airs/nodes/NODE-NOT-FOUND/measurements/summary")
                        .param("date", "2026-05-06")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("노드를 찾을 수 없습니다."));

        verifyNoInteractions(influxDht22Reader);
    }

    @Test
    void getDailySummary_should_return_forbidden_when_node_belongs_to_other_campus() throws Exception {
        Long currentUserId = saveApprovedAdmin("summary-current@example.com", "summary-current-campus");
        Long otherUserId = saveApprovedAdmin("summary-other@example.com", "summary-other-campus");
        saveNodeInstallation(otherUserId, "NODE-SUMMARY-OTHER");
        String accessToken = jwtTokenProvider.generateAccessToken(currentUserId);

        mockMvc.perform(get("/airs/nodes/NODE-SUMMARY-OTHER/measurements/summary")
                        .param("date", "2026-05-06")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("해당 노드에 접근할 수 없습니다."));

        verifyNoInteractions(influxDht22Reader);
    }

    private Long saveApprovedAdmin(String email, String campusName) {
        return transactionTemplate.execute(status -> {
            Campus campus = campusRepository.save(new Campus(campusName, null, null, null));
            User user = userRepository.save(new User(
                    campus,
                    "jaeho",
                    email,
                    "hashed-password",
                    "01012345678",
                    UserRole.ADMIN
            ));
            campusAdminRepository.save(new CampusAdmin(campus, user, true));
            return user.getUserId();
        });
    }

    private void saveNodeInstallation(Long userId, String nodeId) {
        transactionTemplate.executeWithoutResult(status -> {
            User user = userRepository.findById(userId).orElseThrow();
            Campus campus = user.getCampus();
            Building building = buildingRepository.save(new Building(campus, nodeId + "-building"));
            Space space = spaceRepository.save(new Space(
                    campus,
                    building,
                    nodeId + "-space",
                    nodeId + " 공간",
                    "3층",
                    SpaceType.CLASSROOM,
                    null,
                    null
            ));
            AirsNode node = airsNodeRepository.save(new AirsNode(nodeId, null, null));
            nodeInstallationRepository.save(new NodeInstallation(node, space, user, null));
        });
    }
}
