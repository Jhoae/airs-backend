package com.airs.backend.sensor.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import com.airs.backend.global.jwt.CurrentUserPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import com.airs.backend.sensor.dto.DailyDht22SummaryResponse;
import com.airs.backend.sensor.service.Dht22SummaryService;

@ExtendWith(MockitoExtension.class)
class Dht22SummaryControllerTest {

    @Mock
    private Dht22SummaryService dht22SummaryService;

    @InjectMocks
    private Dht22SummaryController dht22SummaryController;

    @Test
    void getDailySummary_should_delegate_to_service_and_return_body() {
        LocalDate date = LocalDate.parse("2026-05-06");
        DailyDht22SummaryResponse expected = new DailyDht22SummaryResponse(
                "node_01", date, 26.1, null, 23.4, 20.1, null, 61.0, null, 52.0, 40.0, null
        );

        when(dht22SummaryService.getDailySummary(1L, "node_01", date)).thenReturn(expected);

        ResponseEntity<DailyDht22SummaryResponse> response = dht22SummaryController.getDailySummary(
                "node_01",
                date,
                new CurrentUserPrincipal(1L)
        );

        assertEquals(200, response.getStatusCode().value());
        assertEquals(expected, response.getBody());
        verify(dht22SummaryService).getDailySummary(1L, "node_01", date);
    }
}
