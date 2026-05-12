package com.airs.backend.sensor.controller;

import com.airs.backend.sensor.dto.Dht22MeasurementItem;
import com.airs.backend.sensor.influx.InfluxDht22Reader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AiDht22QueryControllerMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InfluxDht22Reader influxDht22Reader;

    @Test
    void getRange_should_return_measurements_for_localhost_request_without_jwt() throws Exception {
        Instant from = Instant.parse("2026-05-06T00:00:00Z");
        Instant to = Instant.parse("2026-05-06T01:00:00Z");

        when(influxDht22Reader.readRange("node_01", from, to)).thenReturn(List.of(
                new Dht22MeasurementItem(26.5, 50.3, Instant.parse("2026-05-06T00:00:05Z"))
        ));

        mockMvc.perform(get("/airs/internal/devices/node_01/measurements/range")
                        .param("from", "2026-05-06T00:00:00Z")
                        .param("to", "2026-05-06T01:00:00Z")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value("node_01"))
                .andExpect(jsonPath("$.from").value("2026-05-06T00:00:00Z"))
                .andExpect(jsonPath("$.measurements[0].temperature").value(26.5))
                .andExpect(jsonPath("$.measurements[0].humidity").value(50.3));
    }

    @Test
    void getRange_should_return_forbidden_when_request_is_not_from_localhost() throws Exception {
        mockMvc.perform(get("/airs/internal/devices/node_01/measurements/range")
                        .param("from", "2026-05-06T00:00:00Z")
                        .param("to", "2026-05-06T01:00:00Z")
                        .with(request -> {
                            request.setRemoteAddr("192.168.0.10");
                            return request;
                        }))
                .andExpect(status().isForbidden());

        verifyNoInteractions(influxDht22Reader);
    }

    @Test
    void getRange_should_return_bad_request_when_from_is_missing() throws Exception {
        mockMvc.perform(get("/airs/internal/devices/node_01/measurements/range")
                        .param("to", "2026-05-06T01:00:00Z")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        }))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getRange_should_return_bad_request_when_from_is_after_to() throws Exception {
        mockMvc.perform(get("/airs/internal/devices/node_01/measurements/range")
                        .param("from", "2026-05-06T02:00:00Z")
                        .param("to", "2026-05-06T01:00:00Z")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        }))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("from은 to보다 이후일 수 없습니다."));
    }
}
