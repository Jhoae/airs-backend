package com.airs.backend.device.controller;

import com.airs.backend.device.dto.DeviceRegisterRequest;
import com.airs.backend.device.dto.DeviceUpdateRequest;
import com.airs.backend.device.entity.Device;
import com.airs.backend.device.repository.DeviceRepository;
import com.airs.backend.global.jwt.JwtTokenProvider;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserPreference;
import com.airs.backend.user.repository.UserPreferenceRepository;
import com.airs.backend.user.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class DeviceControllerMySqlTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserPreferenceRepository userPreferenceRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanUp() {
        deviceRepository.deleteAllInBatch();
        userPreferenceRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void registerDevice_should_insert_device_with_user_default_preferences_into_mysql() throws Exception {
        Long userId = saveUserWithPreference(
                "device-register@example.com",
                new BigDecimal("23.5"),
                new BigDecimal("41.0"),
                "AIRS_WIFI"
        );
        String accessToken = jwtTokenProvider.generateAccessToken(userId);
        DeviceRegisterRequest request = new DeviceRegisterRequest("NODE-REGISTER-001");

        mockMvc.perform(post("/airs/devices")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nodeId").value("NODE-REGISTER-001"))
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.preferredTemperature").value(23.5))
                .andExpect(jsonPath("$.preferredHumidity").value(41.0))
                .andExpect(jsonPath("$.wifiSsid").value("AIRS_WIFI"));

        Device savedDevice = deviceRepository.findById("NODE-REGISTER-001")
                .orElseThrow();

        assertEquals(userId, savedDevice.getUser().getUserId());
        assertEquals(new BigDecimal("23.5"), savedDevice.getPreferredTemperature());
        assertEquals(new BigDecimal("41.0"), savedDevice.getPreferredHumidity());
        assertEquals("AIRS_WIFI", savedDevice.getWifiSsid());
        assertNotNull(savedDevice.getCreatedAt());
    }

    @Test
    void registerDevice_should_insert_device_with_null_settings_when_user_default_preferences_are_missing() throws Exception {
        Long userId = saveUser("device-register-null@example.com");
        String accessToken = jwtTokenProvider.generateAccessToken(userId);
        DeviceRegisterRequest request = new DeviceRegisterRequest("NODE-REGISTER-NULL");

        mockMvc.perform(post("/airs/devices")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nodeId").value("NODE-REGISTER-NULL"))
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.preferredTemperature").doesNotExist())
                .andExpect(jsonPath("$.preferredHumidity").doesNotExist())
                .andExpect(jsonPath("$.wifiSsid").doesNotExist());

        Device savedDevice = deviceRepository.findById("NODE-REGISTER-NULL")
                .orElseThrow();

        assertNull(savedDevice.getPreferredTemperature());
        assertNull(savedDevice.getPreferredHumidity());
        assertNull(savedDevice.getWifiSsid());
    }

    @Test
    void registerDevice_should_return_bad_request_when_node_id_is_blank() throws Exception {
        Long userId = saveUser("device-register-invalid@example.com");
        String accessToken = jwtTokenProvider.generateAccessToken(userId);
        DeviceRegisterRequest request = new DeviceRegisterRequest("");

        mockMvc.perform(post("/airs/devices")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(result ->
                        assertInstanceOf(MethodArgumentNotValidException.class, result.getResolvedException())
                );
    }

    @Test
    void registerDevice_should_fail_when_node_id_is_duplicated() throws Exception {
        Long userId = saveUser("device-register-duplicate@example.com");
        String accessToken = jwtTokenProvider.generateAccessToken(userId);
        DeviceRegisterRequest request = new DeviceRegisterRequest("NODE-DUPLICATE");

        mockMvc.perform(post("/airs/devices")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/airs/devices")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 등록된 기기입니다."));
    }

    @Test
    void getMyDevices_should_return_forbidden_when_access_token_is_missing() throws Exception {
        mockMvc.perform(get("/airs/devices"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getMyDevices_should_return_only_current_user_devices() throws Exception {
        Long currentUserId = saveUser("device-list-current@example.com");
        Long otherUserId = saveUser("device-list-other@example.com");

        saveDevice(currentUserId, "NODE-LIST-001", new BigDecimal("23.5"), new BigDecimal("41.0"), "CURRENT_WIFI");
        saveDevice(currentUserId, "NODE-LIST-002", null, null, null);
        saveDevice(otherUserId, "NODE-LIST-OTHER", new BigDecimal("20.0"), new BigDecimal("50.0"), "OTHER_WIFI");

        String accessToken = jwtTokenProvider.generateAccessToken(currentUserId);

        MvcResult result = mockMvc.perform(get("/airs/devices")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        Set<String> nodeIds = new HashSet<>();
        response.forEach(device -> nodeIds.add(device.get("nodeId").asText()));

        assertEquals(2, response.size());
        assertTrue(nodeIds.contains("NODE-LIST-001"));
        assertTrue(nodeIds.contains("NODE-LIST-002"));
    }

    @Test
    void getDevice_should_return_current_user_device_detail() throws Exception {
        Long userId = saveUser("device-detail@example.com");
        saveDevice(userId, "NODE-DETAIL", new BigDecimal("23.5"), new BigDecimal("41.0"), "DETAIL_WIFI");

        String accessToken = jwtTokenProvider.generateAccessToken(userId);

        mockMvc.perform(get("/airs/devices/NODE-DETAIL")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value("NODE-DETAIL"))
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.preferredTemperature").value(23.5))
                .andExpect(jsonPath("$.preferredHumidity").value(41.0))
                .andExpect(jsonPath("$.wifiSsid").value("DETAIL_WIFI"));
    }

    @Test
    void getDevice_should_fail_when_device_belongs_to_other_user() throws Exception {
        Long currentUserId = saveUser("device-detail-current@example.com");
        Long otherUserId = saveUser("device-detail-other@example.com");
        saveDevice(otherUserId, "NODE-DETAIL-OTHER", new BigDecimal("20.0"), new BigDecimal("50.0"), "OTHER_WIFI");

        String accessToken = jwtTokenProvider.generateAccessToken(currentUserId);

        mockMvc.perform(get("/airs/devices/NODE-DETAIL-OTHER")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("해당 기기에 접근할 수 없습니다."));
    }

    @Test
    void updateDevice_should_update_only_non_null_fields() throws Exception {
        Long userId = saveUser("device-update@example.com");
        saveDevice(userId, "NODE-UPDATE", new BigDecimal("23.5"), new BigDecimal("41.0"), "OLD_WIFI");

        String accessToken = jwtTokenProvider.generateAccessToken(userId);
        DeviceUpdateRequest request = new DeviceUpdateRequest(
                new BigDecimal("24.0"),
                null
        );

        mockMvc.perform(patch("/airs/devices/NODE-UPDATE")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value("NODE-UPDATE"))
                .andExpect(jsonPath("$.preferredTemperature").value(24.0))
                .andExpect(jsonPath("$.preferredHumidity").value(41.0))
                .andExpect(jsonPath("$.wifiSsid").value("OLD_WIFI"));

        Device updatedDevice = deviceRepository.findById("NODE-UPDATE")
                .orElseThrow();

        assertEquals(new BigDecimal("24.0"), updatedDevice.getPreferredTemperature());
        assertEquals(new BigDecimal("41.0"), updatedDevice.getPreferredHumidity());
        assertEquals("OLD_WIFI", updatedDevice.getWifiSsid());
    }

    @Test
    void updateDevice_should_return_bad_request_when_request_body_is_invalid() throws Exception {
        Long userId = saveUser("device-update-invalid@example.com");
        saveDevice(userId, "NODE-UPDATE-INVALID", new BigDecimal("23.5"), new BigDecimal("41.0"), "OLD_WIFI");

        String accessToken = jwtTokenProvider.generateAccessToken(userId);
        DeviceUpdateRequest request = new DeviceUpdateRequest(
                new BigDecimal("1234.5"),
                null
        );

        mockMvc.perform(patch("/airs/devices/NODE-UPDATE-INVALID")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(result ->
                        assertInstanceOf(MethodArgumentNotValidException.class, result.getResolvedException())
                );
    }

    @Test
    void updateDevice_should_fail_when_device_belongs_to_other_user() throws Exception {
        Long currentUserId = saveUser("device-update-current@example.com");
        Long otherUserId = saveUser("device-update-other@example.com");
        saveDevice(otherUserId, "NODE-UPDATE-OTHER", new BigDecimal("20.0"), new BigDecimal("50.0"), "OTHER_WIFI");

        String accessToken = jwtTokenProvider.generateAccessToken(currentUserId);
        DeviceUpdateRequest request = new DeviceUpdateRequest(
                new BigDecimal("24.0"),
                null
        );

        mockMvc.perform(patch("/airs/devices/NODE-UPDATE-OTHER")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("해당 기기에 접근할 수 없습니다."));
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

    private Long saveUserWithPreference(
            String email,
            BigDecimal preferredTemperature,
            BigDecimal preferredHumidity,
            String wifiSsid
    ) {
        return transactionTemplate.execute(status -> {
            User user = userRepository.save(new User(
                    "jaeho",
                    email,
                    "hashed-password",
                    null
            ));

            UserPreference userPreference = new UserPreference(
                    preferredTemperature,
                    preferredHumidity,
                    wifiSsid
            );
            userPreference.assignUser(user);
            userPreferenceRepository.save(userPreference);

            return user.getUserId();
        });
    }

    private void saveDevice(
            Long userId,
            String nodeId,
            BigDecimal preferredTemperature,
            BigDecimal preferredHumidity,
            String wifiSsid
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            User user = userRepository.findById(userId)
                    .orElseThrow();

            deviceRepository.save(new Device(
                    nodeId,
                    user,
                    preferredTemperature,
                    preferredHumidity,
                    wifiSsid
            ));
        });
    }
}
