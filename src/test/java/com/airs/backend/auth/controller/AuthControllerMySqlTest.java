package com.airs.backend.auth.controller;

import com.airs.backend.auth.dto.LoginRequest;
import com.airs.backend.auth.dto.SignUpRequest;
import com.airs.backend.global.jwt.JwtTokenProvider;
import com.airs.backend.location.entity.Campus;
import com.airs.backend.location.repository.CampusRepository;
import com.airs.backend.user.entity.CampusAdmin;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserPreference;
import com.airs.backend.user.entity.UserRole;
import com.airs.backend.user.repository.CampusAdminRepository;
import com.airs.backend.user.repository.UserPreferenceRepository;
import com.airs.backend.user.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AuthControllerMySqlTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserPreferenceRepository userPreferenceRepository;

    @Autowired
    private CampusRepository campusRepository;

    @Autowired
    private CampusAdminRepository campusAdminRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanUp() {
        campusAdminRepository.deleteAllInBatch();
        userPreferenceRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        campusRepository.deleteAllInBatch();
    }

    @Test
    void signUp_should_create_user_and_user_preference_in_mysql() throws Exception {
        Long campusId = saveCampus("signup-success-campus");
        SignUpRequest request = new SignUpRequest(
                "signup-success@example.com",
                "Abcd1234!",
                "jaeho",
                "01012345678",
                campusId,
                UserRole.USER
        );

        mockMvc.perform(post("/airs/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("signup-success@example.com"))
                .andExpect(jsonPath("$.nickname").value("jaeho"))
                .andExpect(jsonPath("$.phone").value("01012345678"))
                .andExpect(jsonPath("$.campusId").value(campusId))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.adminApproved").doesNotExist());

        transactionTemplate.executeWithoutResult(status -> {
            User savedUser = userRepository.findByEmail("signup-success@example.com")
                    .orElseThrow();

            UserPreference savedPreference = userPreferenceRepository.findById(savedUser.getUserId())
                    .orElseThrow();

            assertNotNull(savedUser.getUserId());
            assertEquals(campusId, savedUser.getCampusId());
            assertEquals("01012345678", savedUser.getPhone());
            assertEquals(UserRole.USER, savedUser.getRole());
            assertFalse(savedUser.getPasswordHash().equals("Abcd1234!"));
            assertTrue(passwordEncoder.matches("Abcd1234!", savedUser.getPasswordHash()));
            assertEquals(savedUser.getUserId(), savedPreference.getUserId());
        });
    }

    @Test
    void signUp_should_create_pending_admin_when_role_is_admin() throws Exception {
        Long campusId = saveCampus("signup-admin-campus");
        SignUpRequest request = new SignUpRequest(
                "signup-admin@example.com",
                "Abcd1234!",
                "admin",
                "01012345678",
                campusId,
                UserRole.ADMIN
        );

        mockMvc.perform(post("/airs/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("signup-admin@example.com"))
                .andExpect(jsonPath("$.campusId").value(campusId))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.adminApproved").value(false));

        transactionTemplate.executeWithoutResult(status -> {
            User savedUser = userRepository.findByEmail("signup-admin@example.com")
                    .orElseThrow();
            CampusAdmin campusAdmin = campusAdminRepository.findByUser_Id(savedUser.getUserId())
                    .orElseThrow();

            assertEquals(campusId, campusAdmin.getCampus().getCampusId());
            assertFalse(campusAdmin.isApproved());
        });
    }

    @Test
    void signUp_should_return_bad_request_when_request_body_is_invalid() throws Exception {
        SignUpRequest request = new SignUpRequest(
                "invalid-email",
                "1234",
                "a",
                "",
                null,
                null
        );

        mockMvc.perform(post("/airs/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(result ->
                        assertInstanceOf(MethodArgumentNotValidException.class, result.getResolvedException())
                );
    }

    @Test
    void signUp_should_return_conflict_when_email_is_duplicated() throws Exception {
        Long campusId = saveCampus("duplicate-signup-campus");
        SignUpRequest request = new SignUpRequest(
                "duplicate-signup@example.com",
                "Abcd1234!",
                "jaeho",
                "01012345678",
                campusId,
                UserRole.USER
        );

        mockMvc.perform(post("/airs/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/airs/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 사용 중인 이메일입니다."));
    }

    @Test
    void login_should_return_access_token_when_credentials_are_valid() throws Exception {
        Long campusId = saveCampus("login-success-campus");
        Long userId = saveUserWithPreference(
                "jaeho",
                "login-success@example.com",
                "Abcd1234!",
                campusId,
                UserRole.USER
        );

        LoginRequest request = new LoginRequest(
                "login-success@example.com",
                "Abcd1234!"
        );

        mockMvc.perform(post("/airs/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.campusId").value(campusId))
                .andExpect(jsonPath("$.email").value("login-success@example.com"))
                .andExpect(jsonPath("$.nickname").value("jaeho"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.adminApproved").doesNotExist());
    }

    @Test
    void login_should_return_unauthorized_when_password_is_invalid() throws Exception {
        saveUserWithPreference(
                "jaeho",
                "login-fail@example.com",
                "Abcd1234!",
                saveCampus("login-fail-campus"),
                UserRole.USER
        );

        LoginRequest request = new LoginRequest(
                "login-fail@example.com",
                "Wrong1234!"
        );

        mockMvc.perform(post("/airs/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("아이디 또는 비밀번호가 올바르지 않습니다."));
    }

    @Test
    void login_should_return_unauthorized_when_email_is_unknown() throws Exception {
        LoginRequest request = new LoginRequest(
                "missing-user@example.com",
                "Abcd1234!"
        );

        mockMvc.perform(post("/airs/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("아이디 또는 비밀번호가 올바르지 않습니다."));
    }

    @Test
    void getMyInfo_should_return_forbidden_when_access_token_is_missing() throws Exception {
        mockMvc.perform(get("/airs/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getMyInfo_should_return_unauthorized_when_access_token_is_invalid() throws Exception {
        mockMvc.perform(get("/airs/users/me")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("유효하지 않은 토큰입니다."));
    }

    @Test
    void getMyInfo_should_return_current_user_info_when_access_token_is_valid() throws Exception {
        Long campusId = saveCampus("me-success-campus");
        Long userId = saveUserWithPreference(
                "jaeho",
                "me-success@example.com",
                "Abcd1234!",
                campusId,
                UserRole.USER
        );

        LoginRequest loginRequest = new LoginRequest(
                "me-success@example.com",
                "Abcd1234!"
        );

        MvcResult loginResult = mockMvc.perform(post("/airs/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode loginResponse = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String accessToken = loginResponse.get("accessToken").asText();

        mockMvc.perform(get("/airs/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.campusId").value(campusId))
                .andExpect(jsonPath("$.email").value("me-success@example.com"))
                .andExpect(jsonPath("$.nickname").value("jaeho"))
                .andExpect(jsonPath("$.phone").value("01012345678"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void getMyInfo_should_return_not_found_when_user_does_not_exist() throws Exception {
        String accessToken = jwtTokenProvider.generateAccessToken(999_999L);

        mockMvc.perform(get("/airs/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("사용자를 찾을 수 없습니다."));
    }

    private Long saveCampus(String name) {
        return campusRepository.save(new Campus(name, null, null, null)).getCampusId();
    }

    private Long saveUserWithPreference(
            String nickname,
            String email,
            String rawPassword,
            Long campusId,
            UserRole role
    ) {
        return transactionTemplate.execute(status -> {
            Campus campus = campusRepository.findById(campusId).orElseThrow();
            User user = userRepository.save(new User(
                    campus,
                    nickname,
                    email,
                    passwordEncoder.encode(rawPassword),
                    "01012345678",
                    role
            ));

            UserPreference userPreference = new UserPreference(null, null, null);
            userPreference.assignUser(user);
            userPreferenceRepository.save(userPreference);

            return user.getUserId();
        });
    }
}
