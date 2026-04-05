package com.airs.backend.user.repository;

import com.airs.backend.device.repository.DeviceRepository;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("local")
class UserRepositoryMySqlTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserPreferenceRepository userPreferenceRepository;

    @Autowired
    private DeviceRepository deviceRepository;

    @AfterEach
    void cleanUp() {
        deviceRepository.deleteAllInBatch();
        userPreferenceRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void save_should_insert_user_into_mysql_and_remove_it_after_test() {
        User user = new User(
                "jaeho",
                "mysql-user-test@example.com",
                "hashed-password",
                "01012345678"
        );

        User savedUser = userRepository.saveAndFlush(user);

        System.out.println("savedUser = " + savedUser);

        assertNotNull(savedUser.getUserId());
        assertEquals(UserRole.USER, savedUser.getRole());
        assertTrue(userRepository.existsByEmail("mysql-user-test@example.com"));
    }
}
