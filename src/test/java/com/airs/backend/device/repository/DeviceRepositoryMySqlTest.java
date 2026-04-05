package com.airs.backend.device.repository;

import com.airs.backend.device.entity.Device;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserPreference;
import com.airs.backend.user.repository.UserPreferenceRepository;
import com.airs.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("local")
class DeviceRepositoryMySqlTest {

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
    void save_should_insert_device_with_user_default_preferences_and_remove_it_after_test() {
        Long savedUserId = transactionTemplate.execute(status -> {
            User user = new User(
                    "jaeho",
                    "mysql-device-test@example.com",
                    "hashed-password",
                    null
            );
            User savedUser = userRepository.save(user);

            UserPreference userPreference = new UserPreference(
                    new BigDecimal("23.5"),
                    new BigDecimal("41.0"),
                    "AIRS_WIFI"
            );
            userPreference.assignUser(savedUser);
            userPreferenceRepository.save(userPreference);

            Device device = new Device(
                    "NODE-001",
                    savedUser,
                    null,
                    null,
                    null
            );
            device.applyDefaultPreferences(userPreference);
            deviceRepository.save(device);

            return savedUser.getUserId();
        });

        List<Device> devices = deviceRepository.findAllByUser_UserId(savedUserId);

        assertEquals(1, devices.size());

        Device savedDevice = devices.getFirst();

        System.out.println("savedDevice = " + savedDevice);

        assertEquals("NODE-001", savedDevice.getNodeId());
        assertEquals(new BigDecimal("23.5"), savedDevice.getPreferredTemperature());
        assertEquals(new BigDecimal("41.0"), savedDevice.getPreferredHumidity());
        assertEquals("AIRS_WIFI", savedDevice.getWifiSsid());
        assertNotNull(savedDevice.getCreatedAt());
    }
}
