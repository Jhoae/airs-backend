package com.airs.backend.user.repository;

import com.airs.backend.device.repository.DeviceRepository;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserPreference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("local")
class UserPreferenceRepositoryMySqlTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserPreferenceRepository userPreferenceRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanUp() {
        deviceRepository.deleteAllInBatch();
        userPreferenceRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void save_should_insert_user_preference_into_mysql_and_remove_it_after_test() {
        Long savedUserId = transactionTemplate.execute(status -> {
            User user = new User(
                    "jaeho",
                    "mysql-preference-test@example.com",
                    "hashed-password",
                    null
            );
            User savedUser = userRepository.save(user);

            UserPreference userPreference = new UserPreference(
                    new BigDecimal("24.0"),
                    new BigDecimal("45.0"),
                    "AIRS_WIFI"
            );
            userPreference.assignUser(savedUser);

            userPreferenceRepository.save(userPreference);

            return savedUser.getUserId();
        });

        UserPreference savedPreference = userPreferenceRepository.findById(savedUserId)
                .orElseThrow();

        System.out.println("savedPreference = " + savedPreference);

        assertNotNull(savedPreference.getUserId());
        assertEquals(savedUserId, savedPreference.getUserId());
        assertEquals(new BigDecimal("24.0"), savedPreference.getPreferredTemperature());
        assertEquals(new BigDecimal("45.0"), savedPreference.getPreferredHumidity());
        assertEquals("AIRS_WIFI", savedPreference.getWifiSsid());
    }
}
