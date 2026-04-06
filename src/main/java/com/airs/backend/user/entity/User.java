package com.airs.backend.user.entity;

import com.airs.backend.user.UserPolicy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
    @Column(name = "user_id")
    private Long userId;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public User(String nickname, String email, String passwordHash, String phone) {
        this.nickname = nickname;
        this.email = email;
        this.passwordHash = passwordHash;
        this.phone = phone;
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
                "userId=" + userId +
                ", nickname='" + nickname + '\'' +
                ", email='" + email + '\'' +
                ", phone='" + phone + '\'' +
                ", role=" + role +
                ", createdAt=" + createdAt +
                '}';
    }
}
