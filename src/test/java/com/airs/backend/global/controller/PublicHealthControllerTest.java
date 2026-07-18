package com.airs.backend.global.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PublicHealthControllerTest {

    private final PublicHealthController publicHealthController = new PublicHealthController();

    @Test
    void health_returns_up_without_depending_on_external_infrastructure() {
        var response = publicHealthController.health();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("UP", response.getBody().status());
    }
}
