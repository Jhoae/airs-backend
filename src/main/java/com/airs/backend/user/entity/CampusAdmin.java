package com.airs.backend.user.entity;

import com.airs.backend.location.entity.Campus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "campus_admins",
        uniqueConstraints = @UniqueConstraint(columnNames = {"campus_id", "user_id"})
)
public class CampusAdmin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campus_id", nullable = false)
    private Campus campus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CampusAdminStatus status;

    public CampusAdmin(Campus campus, User user, CampusAdminStatus status) {
        this.campus = campus;
        this.user = user;
        this.status = status;
    }

    public boolean isApproved() {
        return status == CampusAdminStatus.APPROVED;
    }

    public boolean isPending() {
        return status == CampusAdminStatus.PENDING;
    }

    public void approve() {
        this.status = CampusAdminStatus.APPROVED;
    }

    public void reject() {
        this.status = CampusAdminStatus.REJECTED;
    }
}
