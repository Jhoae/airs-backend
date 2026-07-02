package com.airs.backend.sensor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

import com.airs.backend.location.entity.Building;
import com.airs.backend.location.entity.Campus;
import com.airs.backend.location.entity.Space;
import com.airs.backend.location.entity.SpaceType;
import com.airs.backend.node.entity.AirsNode;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.node.repository.NodeInstallationRepository;
import com.airs.backend.sensor.dto.DailyDht22SummaryResponse;
import com.airs.backend.sensor.influx.InfluxDht22Reader;
import com.airs.backend.user.entity.CampusAdmin;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserRole;
import com.airs.backend.user.repository.CampusAdminRepository;
import com.airs.backend.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class Dht22SummaryServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CampusAdminRepository campusAdminRepository;

    @Mock
    private NodeInstallationRepository nodeInstallationRepository;

    @Mock
    private InfluxDht22Reader influxDht22Reader;

    @InjectMocks
    private Dht22SummaryService dht22SummaryService;

    @Test
    void getDailySummary_should_return_reader_result_when_device_is_owned_by_user() {
        LocalDate date = LocalDate.parse("2026-05-06");
        Campus campus = campus(10L);
        User user = user(1L, campus, UserRole.ADMIN);
        NodeInstallation installation = installation("node_01", campus, user);

        DailyDht22SummaryResponse expected = new DailyDht22SummaryResponse(
                "node_01", date, 26.1, null, 23.4, 20.1, null, 61.0, null, 52.0, 40.0, null
        );

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(nodeInstallationRepository.findByNode_IdAndActiveTrue("node_01")).thenReturn(Optional.of(installation));
        when(campusAdminRepository.findByUser_Id(1L)).thenReturn(Optional.of(new CampusAdmin(campus, user, true)));
        when(influxDht22Reader.readDailySummary("node_01", date)).thenReturn(expected);

        DailyDht22SummaryResponse actual = dht22SummaryService.getDailySummary(1L, "node_01", date);

        assertEquals(expected, actual);
        verify(influxDht22Reader).readDailySummary("node_01", date);
    }

    @Test
    void getDailySummary_should_fail_when_device_is_not_owned_by_user() {
        LocalDate date = LocalDate.parse("2026-05-06");
        Campus campus = campus(10L);
        User user = user(1L, campus, UserRole.USER);
        NodeInstallation installation = installation("node_01", campus, user);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(nodeInstallationRepository.findByNode_IdAndActiveTrue("node_01")).thenReturn(Optional.of(installation));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> dht22SummaryService.getDailySummary(1L, "node_01", date));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        verifyNoInteractions(influxDht22Reader);
    }

    private Campus campus(Long campusId) {
        Campus campus = new Campus("서강대학교", null, null, null);
        ReflectionTestUtils.setField(campus, "id", campusId);
        return campus;
    }

    private User user(Long userId, Campus campus, UserRole role) {
        User user = new User(campus, "tester", "tester@example.com", "pw", "01012345678", role);
        ReflectionTestUtils.setField(user, "id", userId);
        return user;
    }

    private NodeInstallation installation(String nodeId, Campus campus, User user) {
        Building building = new Building(campus, "김대건관");
        Space space = new Space(campus, building, "K301", "김대건관 301호", "3층", SpaceType.CLASSROOM, null, null);
        AirsNode node = new AirsNode(nodeId, null, null);
        return new NodeInstallation(node, space, user, null);
    }
}
