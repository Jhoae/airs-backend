package com.airs.backend.user.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UserTest {

    @Test
    void constructor_should_initialize_basic_fields() {
        User user = new User(
                "jaeho",
                "test@example.com",
                "hashed-password",
                "01012345678"
        );
        System.out.println("user = " + user);
        assertEquals("jaeho", user.getNickname());
        assertEquals("test@example.com", user.getEmail());
        assertEquals("hashed-password", user.getPasswordHash());
        assertEquals("01012345678", user.getPhone());
    }

    @Test
    void prePersist_should_set_default_role_and_created_at() {
        User user = new User(
                "jaeho",
                "test@example.com",
                "hashed-password",
                null
        );

        user.prePersist();

        System.out.println("user = " + user);
        assertEquals(UserRole.USER, user.getRole());
        assertNotNull(user.getCreatedAt());
    }
}
