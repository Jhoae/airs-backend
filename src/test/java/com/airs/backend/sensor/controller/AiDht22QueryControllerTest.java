package com.airs.backend.sensor.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import com.airs.backend.sensor.dto.Dht22MeasurementItem;
import com.airs.backend.sensor.dto.Dht22RangeResponse;
import com.airs.backend.sensor.service.AiDht22QueryService;

@ExtendWith(MockitoExtension.class)
class AiDht22QueryControllerTest {

    @Mock
    private AiDht22QueryService aiDht22QueryService;

    @InjectMocks
    private AiDht22QueryController aiDht22QueryController;

    @Test
    void getRange_should_delegate_to_service_and_return_body() {
        Instant from = Instant.parse("2026-05-06T00:00:00Z");
        Instant to = Instant.parse("2026-05-06T01:00:00Z");
        Dht22RangeResponse expected = new Dht22RangeResponse(
                "node_01",
                from,
                to,
                List.of(new Dht22MeasurementItem(26.5, 50.3, Instant.parse("2026-05-06T00:00:05Z")))
        );

        when(aiDht22QueryService.getRange("node_01", from, to)).thenReturn(expected);

        ResponseEntity<Dht22RangeResponse> response =
                aiDht22QueryController.getRange("node_01", from, to);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(expected, response.getBody());
        verify(aiDht22QueryService).getRange("node_01", from, to);
    }
}
