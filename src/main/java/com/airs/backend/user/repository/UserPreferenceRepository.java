package com.airs.backend.user.repository;

import com.airs.backend.user.entity.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;


public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {
}
