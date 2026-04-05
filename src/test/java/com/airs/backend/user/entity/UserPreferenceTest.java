package com.airs.backend.user.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class UserPreferenceTest {

    @Test
    void constructor_should_initialize_preference_fields() {
        UserPreference userPreference = new UserPreference(
                new BigDecimal("23.5"),
                new BigDecimal("41.0"),
                "AIRS_WIFI"
        );

        System.out.println("userPreference = " + userPreference);
        assertEquals(new BigDecimal("23.5"), userPreference.getPreferredTemperature());
        assertEquals(new BigDecimal("41.0"), userPreference.getPreferredHumidity());
        assertEquals("AIRS_WIFI", userPreference.getWifiSsid());
    }

    @Test
    void assignUser_should_link_both_sides() {
        User user = new User(
                "jaeho",
                "test@example.com",
                "hashed-password",
                null
        );
        UserPreference userPreference = new UserPreference(
                new BigDecimal("23.5"),
                new BigDecimal("41.0"),
                "AIRS_WIFI"
        );

        userPreference.assignUser(user);

        System.out.println("userPreference = " + userPreference);
        assertSame(user, userPreference.getUser());
    }
}
