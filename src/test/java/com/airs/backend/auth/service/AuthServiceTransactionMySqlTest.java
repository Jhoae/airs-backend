package com.airs.backend.auth.service;

import com.airs.backend.auth.dto.SignUpRequest;
import com.airs.backend.device.repository.DeviceRepository;
import com.airs.backend.user.entity.UserPreference;
import com.airs.backend.user.repository.UserPreferenceRepository;
import com.airs.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("local")
class AuthServiceTransactionMySqlTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserPreferenceRepository userPreferenceRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @SpyBean
    private UserPreferenceRepository userPreferenceRepositorySpy;

    @AfterEach
    void cleanUp() {
        Mockito.reset(userPreferenceRepositorySpy);
        deviceRepository.deleteAllInBatch();
        userPreferenceRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void signUp_should_rollback_user_insert_when_user_preference_save_fails() {
        SignUpRequest request = new SignUpRequest(
                "rollback-signup@example.com",
                "Abcd1234!",
                "jaeho"
        );

        doThrow(new RuntimeException("회원가입 중 강제 실패"))
                .when(userPreferenceRepositorySpy)
                .save(any(UserPreference.class));

        assertThrows(RuntimeException.class, () -> authService.signUp(request));
        assertFalse(userRepository.existsByEmail("rollback-signup@example.com"));
    }
}
