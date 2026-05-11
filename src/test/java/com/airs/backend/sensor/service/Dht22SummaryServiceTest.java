package com.airs.backend.sensor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.airs.backend.device.entity.Device;
import com.airs.backend.device.repository.DeviceRepository;
import com.airs.backend.sensor.dto.DailyDht22SummaryResponse;
import com.airs.backend.sensor.influx.InfluxDht22Reader;
import com.airs.backend.user.entity.User;

@ExtendWith(MockitoExtension.class)
class Dht22SummaryServiceTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private InfluxDht22Reader influxDht22Reader;

    @InjectMocks
    private Dht22SummaryService dht22SummaryService;

    @Test
    void getDailySummary_should_return_reader_result_when_device_is_owned_by_user() {
        LocalDate date = LocalDate.parse("2026-05-06");
        User user = new User("tester", "tester@example.com", "pw", "01012345678");
        ReflectionTestUtils.setField(user, "userId", 1L);

        Device device = new Device("node_01", user, BigDecimal.valueOf(24.0), BigDecimal.valueOf(50.0), "wifi");

        DailyDht22SummaryResponse expected = new DailyDht22SummaryResponse(
                "node_01", date, 26.1, null, 23.4, 20.1, null, 61.0, null, 52.0, 40.0, null
        );

        when(deviceRepository.findById("node_01")).thenReturn(Optional.of(device));
        when(influxDht22Reader.readDailySummary("node_01", date)).thenReturn(expected);

        DailyDht22SummaryResponse actual = dht22SummaryService.getDailySummary(1L, "node_01", date);

        assertEquals(expected, actual);
        verify(influxDht22Reader).readDailySummary("node_01", date);
    }

    @Test
    void getDailySummary_should_fail_when_device_is_not_owned_by_user() {
        LocalDate date = LocalDate.parse("2026-05-06");
        User user = new User("tester", "tester@example.com", "pw", "01012345678");
        ReflectionTestUtils.setField(user, "userId", 2L);

        Device device = new Device("node_01", user, BigDecimal.valueOf(24.0), BigDecimal.valueOf(50.0), "wifi");

        when(deviceRepository.findById("node_01")).thenReturn(Optional.of(device));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> dht22SummaryService.getDailySummary(1L, "node_01", date));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        verifyNoInteractions(influxDht22Reader);
    }
}
