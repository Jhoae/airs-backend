package com.airs.backend.node.entity;

import com.airs.backend.location.entity.Space;
import com.airs.backend.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "node_installations")
public class NodeInstallation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "node_id", nullable = false)
    private AirsNode node;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "space_id", nullable = false)
    private Space space;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "installed_by_user_id")
    private User installedByUser;

    @Column(name = "installed_at", nullable = false, updatable = false)
    private LocalDateTime installedAt;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public NodeInstallation(
            AirsNode node,
            Space space,
            User installedByUser,
            LocalDateTime installedAt
    ) {
        this.node = node;
        this.space = space;
        this.installedByUser = installedByUser;
        this.installedAt = installedAt;
    }

    public void deactivate() {
        this.active = false;
    }

    @PrePersist
    protected void prePersist() {
        if (this.installedAt == null) {
            this.installedAt = LocalDateTime.now();
        }
    }
}
