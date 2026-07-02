package com.airs.backend.global.init;

import com.airs.backend.location.entity.Campus;
import com.airs.backend.location.repository.CampusRepository;
import com.airs.backend.user.entity.CampusAdmin;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserPreference;
import com.airs.backend.user.entity.UserRole;
import com.airs.backend.user.repository.CampusAdminRepository;
import com.airs.backend.user.repository.UserPreferenceRepository;
import com.airs.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class SogangRootAdminSeeder implements ApplicationRunner {

    private static final String DEFAULT_CAMPUS_NAME = "서강대학교";
    private static final String DEFAULT_ROOT_ADMIN_LOGIN_ID = "asdf";
    private static final String DEFAULT_ROOT_ADMIN_PASSWORD = "1234";
    private static final String DEFAULT_ROOT_ADMIN_NICKNAME = "홍길동";

    private final CampusRepository campusRepository;
    private final UserRepository userRepository;
    private final UserPreferenceRepository userPreferenceRepository;
    private final CampusAdminRepository campusAdminRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Campus campus = campusRepository.findByName(DEFAULT_CAMPUS_NAME)
                .orElseGet(() -> campusRepository.save(new Campus(DEFAULT_CAMPUS_NAME, null, null, null)));

        User rootAdmin = userRepository.findByEmail(DEFAULT_ROOT_ADMIN_LOGIN_ID)
                .orElseGet(() -> createRootAdmin(campus));

        campusAdminRepository.findByUser_Id(rootAdmin.getUserId())
                .orElseGet(() -> campusAdminRepository.save(new CampusAdmin(campus, rootAdmin, true)));
    }

    private User createRootAdmin(Campus campus) {
        User rootAdmin = userRepository.save(new User(
                campus,
                DEFAULT_ROOT_ADMIN_NICKNAME,
                DEFAULT_ROOT_ADMIN_LOGIN_ID,
                passwordEncoder.encode(DEFAULT_ROOT_ADMIN_PASSWORD),
                null,
                UserRole.ROOT_ADMIN
        ));

        UserPreference userPreference = new UserPreference(null, null, null);
        userPreference.assignUser(rootAdmin);
        userPreferenceRepository.save(userPreference);

        return rootAdmin;
    }
}
