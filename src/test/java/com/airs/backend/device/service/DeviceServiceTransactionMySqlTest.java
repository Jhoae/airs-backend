package com.airs.backend.device.service;

import com.airs.backend.device.entity.Device;
import com.airs.backend.device.repository.DeviceRepository;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.repository.UserPreferenceRepository;
import com.airs.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("local")
class DeviceServiceTransactionMySqlTest {

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
    void updateDevice_should_rollback_when_exception_occurs_after_device_settings_change() {
        Long userId = saveUserAndDevice();

        assertThrows(RuntimeException.class, () ->
                transactionTemplate.executeWithoutResult(status -> {
                    Device device = deviceRepository.findById("NODE-ROLLBACK")
                            .orElseThrow();

                    device.updateSettings(
                            new BigDecimal("24.0"),
                            new BigDecimal("42.0")
                    );

                    throw new RuntimeException("기기 수정 중 강제 실패");
                })
        );

        Device reloadedDevice = deviceRepository.findById("NODE-ROLLBACK")
                .orElseThrow();

        assertEquals(userId, reloadedDevice.getUser().getUserId());
        assertEquals(new BigDecimal("23.5"), reloadedDevice.getPreferredTemperature());
        assertEquals(new BigDecimal("41.0"), reloadedDevice.getPreferredHumidity());
        assertEquals("OLD_WIFI", reloadedDevice.getWifiSsid());
    }

    private Long saveUserAndDevice() {
        return transactionTemplate.execute(status -> {
            User user = userRepository.save(new User(
                    "jaeho",
                    "device-rollback@example.com",
                    "hashed-password",
                    null
            ));

            Device device = new Device(
                    "NODE-ROLLBACK",
                    user,
                    new BigDecimal("23.5"),
                    new BigDecimal("41.0"),
                    "OLD_WIFI"
            );
            deviceRepository.save(device);

            return user.getUserId();
        });
    }
}
