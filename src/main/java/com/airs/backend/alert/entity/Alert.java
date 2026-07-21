package com.airs.backend.alert.entity;

import com.airs.backend.location.entity.Campus;
import com.airs.backend.location.entity.Space;
import com.airs.backend.node.entity.AirsNode;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "alerts")
public class Alert {

    // alerts 테이블의 대리 키다.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 알림이 발생한 캠퍼스를 가리키는 필수 관계다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campus_id", nullable = false)
    private Campus campus;

    // 알림과 연결된 공간이며 캠퍼스 전체 알림이면 null일 수 있다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "space_id")
    private Space space;

    // 알림을 감지한 노드이며 공간 단위 알림이면 null일 수 있다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "node_id")
    private AirsNode node;

    // 환기·공조·오프라인처럼 알림의 업무 유형을 문자열 enum으로 저장한다.
    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false, length = 60)
    private AlertType alertType;

    // 긴급·주의·정보 중 화면 표시 우선순위를 저장한다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AlertSeverity severity;

    // 활성 또는 해결 상태를 문자열 enum으로 저장한다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AlertStatus status;

    // 알림을 볼 수 있는 사용자 범위를 문자열 enum으로 저장한다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AlertAudience audience;

    // 목록에서 바로 보여줄 알림 제목이다.
    @Column(nullable = false, length = 180)
    private String title;

    // 알림 원인과 권장 행동을 담는 상세 문구다.
    @Column(length = 1000)
    private String message;

    // 임계값 판단에 사용한 측정 항목 이름이다.
    @Column(name = "metric_name", length = 50)
    private String metricName;

    // 발생 시점의 측정 수치를 소수 셋째 자리까지 저장한다.
    @Column(name = "metric_value", precision = 12, scale = 3)
    private BigDecimal metricValue;

    // 측정 수치에 대응하는 단위 문자열이다.
    @Column(name = "metric_unit", length = 30)
    private String metricUnit;

    // 같은 노드·유형의 ACTIVE 알림을 하나로 유지하는 중복 방지 키다.
    @Column(name = "dedup_key", nullable = false, length = 180)
    private String dedupKey;

    // 이 알림이 처음 ACTIVE가 된 시각이며 이후 변경할 수 없다.
    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    // 같은 알림 조건을 마지막으로 다시 감지한 시각이다.
    @Column(name = "last_detected_at", nullable = false)
    private LocalDateTime lastDetectedAt;

    // ACTIVE 조건이 해소된 시각이며 미해결 상태에서는 null이다.
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    // 엔티티가 처음 저장된 시각이며 이후 변경할 수 없다.
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 엔티티가 마지막으로 갱신된 시각이다.
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Alert(
            Campus campus,
            Space space,
            AirsNode node,
            AlertType alertType,
            AlertSeverity severity,
            AlertAudience audience,
            String title,
            String message,
            String metricName,
            BigDecimal metricValue,
            String metricUnit,
            String dedupKey,
            LocalDateTime detectedAt
    ) {
        // 발생 캠퍼스 관계를 보관한다.
        this.campus = campus;
        // 발생 공간 관계를 보관한다.
        this.space = space;
        // 감지 노드 관계를 보관한다.
        this.node = node;
        // 알림 업무 유형을 보관한다.
        this.alertType = alertType;
        // 최초 심각도를 보관한다.
        this.severity = severity;
        // 알림 공개 대상을 보관한다.
        this.audience = audience;
        // 화면용 제목을 보관한다.
        this.title = title;
        // 화면용 상세 문구를 보관한다.
        this.message = message;
        // 측정 항목 이름을 보관한다.
        this.metricName = metricName;
        // 발생 시점 측정값을 보관한다.
        this.metricValue = metricValue;
        // 측정 단위를 보관한다.
        this.metricUnit = metricUnit;
        // ACTIVE 중복 방지 키를 보관한다.
        this.dedupKey = dedupKey;
        // 최초 감지 시각을 시작 시각으로 기록한다.
        this.startedAt = detectedAt;
        // 최초 감지 시각을 마지막 감지 시각으로도 기록한다.
        this.lastDetectedAt = detectedAt;
    }

    public void refresh(
            AlertSeverity severity,
            String title,
            String message,
            String metricName,
            BigDecimal metricValue,
            String metricUnit,
            LocalDateTime detectedAt
    ) {
        // 최신 정책 결과의 심각도로 갱신한다.
        this.severity = severity;
        // 최신 제목으로 갱신한다.
        this.title = title;
        // 최신 상세 문구로 갱신한다.
        this.message = message;
        // 최신 측정 항목 이름으로 갱신한다.
        this.metricName = metricName;
        // 최신 측정값으로 갱신한다.
        this.metricValue = metricValue;
        // 최신 측정 단위로 갱신한다.
        this.metricUnit = metricUnit;
        // 같은 문제가 재발하면 다시 활성 상태로 되돌린다.
        this.status = AlertStatus.ACTIVE;
        // 재발한 알림에는 기존 해결 시각을 남기지 않는다.
        this.resolvedAt = null;
        // 마지막 재감지 시각을 갱신한다.
        this.lastDetectedAt = detectedAt;
    }

    public void resolve(LocalDateTime resolvedAt) {
        // 감지 조건이 해소된 알림을 이력 상태로 전환한다.
        this.status = AlertStatus.RESOLVED;
        // 해결 처리한 시각을 기록한다.
        this.resolvedAt = resolvedAt;
    }

    // 새 엔티티 저장 직전에 기본 상태와 생성 시각을 채운다.
    @PrePersist
    protected void prePersist() {
        // 모든 생성 시각 필드에 같은 현재 시각을 사용한다.
        LocalDateTime now = LocalDateTime.now();
        // 생성자가 생략한 상태성 필드를 기본값으로 채운다.
        fillDefaults(now);
        // 최초 저장 시각을 기록한다.
        this.createdAt = now;
        // 최초 갱신 시각도 생성 시각과 같게 기록한다.
        this.updatedAt = now;
    }

    // 기존 엔티티 갱신 직전에 기본 상태와 갱신 시각을 보정한다.
    @PreUpdate
    protected void preUpdate() {
        // 누락된 상태성 필드를 현재 시각 기준으로 보정한다.
        fillDefaults(LocalDateTime.now());
        // 마지막 수정 시각을 현재 시각으로 기록한다.
        this.updatedAt = LocalDateTime.now();
    }

    private void fillDefaults(LocalDateTime now) {
        // 상태가 없는 새 알림은 활성 상태로 시작한다.
        if (this.status == null) {
            this.status = AlertStatus.ACTIVE;
        }
        // 수신 대상이 없으면 관리자용 알림으로 제한한다.
        if (this.audience == null) {
            this.audience = AlertAudience.ADMIN;
        }
        // 시작 시각이 없으면 저장 직전 시각으로 채운다.
        if (this.startedAt == null) {
            this.startedAt = now;
        }
        // 마지막 감지 시각이 없으면 시작 시각과 일치시킨다.
        if (this.lastDetectedAt == null) {
            this.lastDetectedAt = this.startedAt;
        }
    }
}
