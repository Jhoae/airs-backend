package com.airs.backend.node.dto.detail;

import com.airs.backend.alert.entity.AlertSeverity;
import com.airs.backend.alert.entity.AlertType;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class AdminNodeAlertResponse {

    private Long alertId;
    private AlertType alertType;
    private AlertSeverity severity;
    private String title;
    private String message;
    private LocalDateTime lastDetectedAt;
}
