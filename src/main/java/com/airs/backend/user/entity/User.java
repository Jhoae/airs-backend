package com.airs.backend.user.entity;

import com.airs.backend.location.entity.Campus;
import com.airs.backend.user.UserPolicy;
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
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Size(
            min = UserPolicy.NICKNAME_MIN_LENGTH,
            max = UserPolicy.NICKNAME_MAX_LENGTH
    )
    @Column(nullable = false, length = UserPolicy.NICKNAME_MAX_LENGTH)
    private String nickname;

    @Column(nullable = false, length = UserPolicy.EMAIL_MAX_LENGTH, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(length = 20)
    private String phone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campus_id")
    private Campus campus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public User(String nickname, String email, String passwordHash, String phone) {
        this(null, nickname, email, passwordHash, phone, null);
    }

    public User(Campus campus, String nickname, String email, String passwordHash, String phone, UserRole role) {
        this.campus = campus;
        this.nickname = nickname;
        this.email = email;
        this.passwordHash = passwordHash;
        this.phone = phone;
        this.role = role;
    }

    public Long getCampusId() {
        if (campus == null) {
            return null;
        }
        return campus.getCampusId();
    }

    public Long getUserId() {
        return id;
    }

    @PrePersist
    protected void prePersist() {
        if (this.role == null) {
            this.role = UserRole.USER;
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    @Override
    public String toString() {
        return "User{" +
                "id=" + id +
                ", nickname='" + nickname + '\'' +
                ", email='" + email + '\'' +
                ", phone='" + phone + '\'' +
                ", campusId=" + getCampusId() +
                ", role=" + role +
                ", createdAt=" + createdAt +
                '}';
    }
}
