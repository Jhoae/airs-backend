package com.airs.backend.alert.service;

import com.airs.backend.alert.entity.Alert;
import com.airs.backend.alert.entity.AlertAudience;
import com.airs.backend.alert.entity.AlertSeverity;
import com.airs.backend.alert.entity.AlertStatus;
import com.airs.backend.alert.entity.AlertType;
import com.airs.backend.alert.repository.AlertRepository;
import com.airs.backend.location.entity.Building;
import com.airs.backend.location.entity.Campus;
import com.airs.backend.location.entity.Space;
import com.airs.backend.location.entity.SpaceType;
import com.airs.backend.node.entity.AirsNode;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 실제 Wi-Fi RSSI가 INFO 알림 lifecycle을 정확히 바꾸는지 검증한다.
@ExtendWith(MockitoExtension.class)
class WeakWifiAlertServiceTest {

    // alerts 조회와 저장을 대체한다.
    @Mock
    private AlertRepository alertRepository;

    // 실제 서비스의 Wi-Fi 기준과 lifecycle 로직을 검증한다.
    @InjectMocks
    private WeakWifiAlertService service;

    // -76dBm 이하가 처음 수신되면 WEAK_WIFI 정보 알림을 생성한다.
    @Test
    void sync_should_create_info_alert_at_or_below_minus76_dbm() {
        // node_01이 K301에 설치된 실제 운영 형태의 fixture를 만든다.
        NodeInstallation installation = installation();
        // 새 lifecycle이므로 기존 ACTIVE 행은 없다고 가정한다.
        when(alertRepository.findByDedupKeyAndStatus("node_01:WEAK_WIFI", AlertStatus.ACTIVE))
                .thenReturn(Optional.empty());
        // 저장될 alerts 행을 직접 검사하기 위한 captor를 만든다.
        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);

        // 실제 약함 기준과 같은 -76dBm telemetry를 전달한다.
        service.sync(installation, -76, LocalDateTime.parse("2026-07-27T14:30:00"));

        // 새 행을 한 번 저장했는지 확인한다.
        verify(alertRepository).save(captor.capture());
        // 저장 요청에 전달한 alerts entity를 꺼낸다.
        Alert saved = captor.getValue();
        // 유형은 펌웨어나 일반 안내가 아닌 Wi-Fi 약함으로 고정된다.
        assertEquals(AlertType.WEAK_WIFI, saved.getAlertType());
        // 정보 카드에만 나타나는 INFO 심각도를 확인한다.
        assertEquals(AlertSeverity.INFO, saved.getSeverity());
        // 실제 RSSI field를 metric으로 보존했는지 확인한다.
        assertEquals("wifi_signal_dbm", saved.getMetricName());
        // 화면이 -76 dBm을 그대로 표시할 수 있는지 확인한다.
        assertEquals(new BigDecimal("-76"), saved.getMetricValue());
        // 동일 노드의 중복 생성 방지 키를 확인한다.
        assertEquals("node_01:WEAK_WIFI", saved.getDedupKey());
    }

    // 경계 구간에서는 기존 행을 유지하고 -73dBm 초과일 때만 완료한다.
    @Test
    void sync_should_keep_then_resolve_existing_alert_with_hysteresis() {
        // node_01이 K301에 설치된 fixture를 만든다.
        NodeInstallation installation = installation();
        // 이미 활성인 Wi-Fi 약함 행을 만든다.
        Alert existingAlert = weakWifiAlert(installation);
        // 각 telemetry에서 같은 ACTIVE 행을 찾는 상황을 만든다.
        when(alertRepository.findByDedupKeyAndStatus("node_01:WEAK_WIFI", AlertStatus.ACTIVE))
                .thenReturn(Optional.of(existingAlert));

        // -74dBm은 생성 기준보다 좋지만 해결 기준보다 나쁘므로 ACTIVE를 유지한다.
        service.sync(installation, -74, LocalDateTime.parse("2026-07-27T14:31:00"));

        // 기존 행이 INFO 상태로 유지되고 최신 RSSI만 바뀌었는지 확인한다.
        assertEquals(AlertStatus.ACTIVE, existingAlert.getStatus());
        assertEquals(new BigDecimal("-74"), existingAlert.getMetricValue());
        assertNull(existingAlert.getResolvedAt());

        // -72dBm은 해결 기준을 넘으므로 동일 행을 완료 이력으로 전환한다.
        service.sync(installation, -72, LocalDateTime.parse("2026-07-27T14:32:00"));

        // 새 행을 만들지 않고 기존 lifecycle만 종료했는지 확인한다.
        assertEquals(AlertStatus.RESOLVED, existingAlert.getStatus());
        assertEquals(LocalDateTime.parse("2026-07-27T14:32:00"), existingAlert.getResolvedAt());
    }

    // 테스트용 캠퍼스·공간·노드 설치 관계를 만든다.
    private NodeInstallation installation() {
        // 관리자 접근 범위를 나타낼 캠퍼스를 만든다.
        Campus campus = new Campus("서강대학교", null, null, 500);
        // JPA 저장 전 테스트에서도 FK id를 사용할 수 있게 id를 주입한다.
        ReflectionTestUtils.setField(campus, "id", 1L);
        // 캠퍼스 안의 건물 관계를 만든다.
        Building building = new Building(campus, "김대건관");
        // 실제 목록에 표시되는 K301 공간을 만든다.
        Space space = new Space(campus, building, "K301", "301호", "3층", SpaceType.CLASSROOM, null, null);
        // JPA 저장 전 테스트에서도 공간 id를 사용할 수 있게 주입한다.
        ReflectionTestUtils.setField(space, "id", 301L);
        // MQTT telemetry의 node_01과 같은 노드 entity를 만든다.
        AirsNode node = new AirsNode("node_01", "ESP32-C3", "v1.0.0");
        // 설치 이력을 만든 관리자를 준비한다.
        User admin = new User(campus, "관리자", "admin@example.com", "hash", "01012345678", UserRole.ADMIN);
        // 노드와 공간을 잇는 활성 설치 관계를 반환한다.
        return new NodeInstallation(node, space, admin, LocalDateTime.parse("2026-07-01T09:00:00"));
    }

    // 최초 ACTIVE Wi-Fi 약함 행을 만든다.
    private Alert weakWifiAlert(NodeInstallation installation) {
        // 생성자에 실제 alerts FK·metric·dedup 값을 전달한다.
        Alert alert = new Alert(
                installation.getSpace().getCampus(),
                installation.getSpace(),
                installation.getNode(),
                AlertType.WEAK_WIFI,
                AlertSeverity.INFO,
                AlertAudience.ADMIN,
                "Wi-Fi 신호 약함",
                "Wi-Fi 신호가 약합니다. 현재 -76 dBm입니다.",
                "wifi_signal_dbm",
                new BigDecimal("-76"),
                "dBm",
                "node_01:WEAK_WIFI",
                LocalDateTime.parse("2026-07-27T14:20:00")
        );
        // JPA 저장 전 테스트에서도 ACTIVE 상태를 명시적으로 초기화한다.
        alert.refresh(
                AlertSeverity.INFO,
                "Wi-Fi 신호 약함",
                "Wi-Fi 신호가 약합니다. 현재 -76 dBm입니다.",
                "wifi_signal_dbm",
                new BigDecimal("-76"),
                "dBm",
                LocalDateTime.parse("2026-07-27T14:20:00")
        );
        // 활성 lifecycle fixture를 반환한다.
        return alert;
    }
}
