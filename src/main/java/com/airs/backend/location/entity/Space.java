package com.airs.backend.location.entity;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "spaces",
        uniqueConstraints = @UniqueConstraint(columnNames = {"campus_id", "code"})
)
public class Space {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campus_id", nullable = false)
    private Campus campus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "building_id", nullable = false)
    private Building building;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "floor_label", length = 50)
    private String floorLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "space_type", nullable = false, length = 40)
    private SpaceType spaceType;

    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Space(
            Campus campus,
            Building building,
            String code,
            String name,
            String floorLabel,
            SpaceType spaceType,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
        this.campus = campus;
        this.building = building;
        this.code = code;
        this.name = name;
        this.floorLabel = floorLabel;
        this.spaceType = spaceType;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    @PrePersist
    protected void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.spaceType == null) {
            this.spaceType = SpaceType.OTHER;
        }
        if (this.createdAt == null) {
            this.createdAt = now;
        }
    }
}
