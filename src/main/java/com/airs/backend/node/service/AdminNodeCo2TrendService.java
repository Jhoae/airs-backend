package com.airs.backend.node.service;

import com.airs.backend.admin.service.AdminAccessService;
import com.airs.backend.node.dto.trend.AdminNodeCo2TrendPointResponse;
import com.airs.backend.node.dto.trend.AdminNodeCo2TrendResponse;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.node.repository.NodeInstallationRepository;
import com.airs.backend.sensor.dto.Co2TrendItem;
import com.airs.backend.sensor.influx.InfluxDht22Reader;
import com.airs.backend.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminNodeCo2TrendService {

    private static final int DEFAULT_HOURS = 6;
    private static final int MAX_HOURS = 24;
    private static final String DEFAULT_WINDOW = "5m";

    private final AdminAccessService adminAccessService;
    private final NodeInstallationRepository nodeInstallationRepository;
    private final InfluxDht22Reader influxDht22Reader;

    public AdminNodeCo2TrendResponse getCo2Trend(
            Long userId,
            String nodeId,
            Integer hours,
            String window
    ) {
        int resolvedHours = resolveHours(hours);
        String resolvedWindow = resolveWindow(window);
        User user = adminAccessService.getApprovedAdmin(userId);
        NodeInstallation installation = nodeInstallationRepository.findByNode_IdAndActiveTrue(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "노드를 찾을 수 없습니다."));

        validateSameCampus(user, installation);

        Instant to = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant from = to.minus(resolvedHours, ChronoUnit.HOURS);
        List<AdminNodeCo2TrendPointResponse> points = influxDht22Reader
                .readCo2Trend(nodeId, from, to, resolvedWindow)
                .stream()
                .map(this::toPointResponse)
                .toList();

        return new AdminNodeCo2TrendResponse(
                nodeId,
                from,
                to,
                resolvedWindow,
                points
        );
    }

    private int resolveHours(Integer hours) {
        if (hours == null) {
            return DEFAULT_HOURS;
        }
        if (hours <= 0 || hours > MAX_HOURS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "hours는 1~24 사이여야 합니다.");
        }
        return hours;
    }

    private String resolveWindow(String window) {
        if (window == null || window.isBlank()) {
            return DEFAULT_WINDOW;
        }
        if (!window.matches("\\d+[smhd]")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "window 형식이 올바르지 않습니다.");
        }
        return window;
    }

    private void validateSameCampus(User user, NodeInstallation installation) {
        Long userCampusId = user.getCampusId();
        Long nodeCampusId = installation.getSpace().getCampus().getCampusId();

        if (userCampusId == null || !userCampusId.equals(nodeCampusId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 노드에 접근할 수 없습니다.");
        }
    }

    private AdminNodeCo2TrendPointResponse toPointResponse(Co2TrendItem item) {
        return new AdminNodeCo2TrendPointResponse(
                item.getTimestamp(),
                item.getCo2Ppm()
        );
    }
}
