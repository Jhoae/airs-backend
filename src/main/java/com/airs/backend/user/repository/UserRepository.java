package com.airs.backend.user.repository;

import com.airs.backend.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    // 회원가입시 이메일 중복 체크
    boolean existsByEmail(String email);

    // unique한 email 값으로 유저 찾기
    Optional<User> findByEmail(String email);
}
