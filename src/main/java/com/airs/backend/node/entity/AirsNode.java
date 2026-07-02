package com.airs.backend.node.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "airs_nodes")
public class AirsNode {

    @Id
    @Column(name = "id", nullable = false, length = 80)
    private String id;

    @Column(name = "hardware_version", length = 50)
    private String hardwareVersion;

    @Column(name = "firmware_version", length = 50)
    private String firmwareVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public AirsNode(String id, String hardwareVersion, String firmwareVersion) {
        this.id = id;
        this.hardwareVersion = hardwareVersion;
        this.firmwareVersion = firmwareVersion;
    }

    public void updateVersions(String hardwareVersion, String firmwareVersion) {
        if (hardwareVersion != null && !hardwareVersion.isBlank()) {
            this.hardwareVersion = hardwareVersion;
        }
        if (firmwareVersion != null && !firmwareVersion.isBlank()) {
            this.firmwareVersion = firmwareVersion;
        }
    }

    @PrePersist
    protected void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
