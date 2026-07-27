package com.airs.backend.admin.service;

import com.airs.backend.location.entity.Campus;
import com.airs.backend.user.entity.CampusAdmin;
import com.airs.backend.user.entity.CampusAdminStatus;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserRole;
import com.airs.backend.user.repository.CampusAdminRepository;
import com.airs.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAccessServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CampusAdminRepository campusAdminRepository;

    @InjectMocks
    private AdminAccessService adminAccessService;

    @Test
    void getApprovedAdmin_allows_root_admin_without_campus_admin_approval_row() {
        Campus campus = campus(1L);
        User rootAdmin = user(1L, campus, UserRole.ROOT_ADMIN);
        when(userRepository.findById(1L)).thenReturn(Optional.of(rootAdmin));

        User actual = adminAccessService.getApprovedAdmin(1L);

        assertSame(rootAdmin, actual);
        verifyNoInteractions(campusAdminRepository);
    }

    @Test
    void getApprovedAdmin_allows_approved_admin_in_own_campus() {
        Campus campus = campus(1L);
        User admin = user(1L, campus, UserRole.ADMIN);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(campusAdminRepository.findByUser_Id(1L))
                .thenReturn(Optional.of(new CampusAdmin(campus, admin, CampusAdminStatus.APPROVED)));

        User actual = adminAccessService.getApprovedAdmin(1L);

        assertSame(admin, actual);
    }

    @Test
    void getApprovedAdmin_rejects_pending_admin() {
        Campus campus = campus(1L);
        User admin = user(1L, campus, UserRole.ADMIN);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(campusAdminRepository.findByUser_Id(1L))
                .thenReturn(Optional.of(new CampusAdmin(campus, admin, CampusAdminStatus.PENDING)));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> adminAccessService.getApprovedAdmin(1L)
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
    }

    @Test
    void getApprovedAdmin_rejects_user_even_when_a_campus_is_assigned() {
        User user = user(1L, campus(1L), UserRole.USER);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> adminAccessService.getApprovedAdmin(1L)
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        verifyNoInteractions(campusAdminRepository);
    }

    @Test
    void requireSameCampus_rejects_other_campus() {
        Campus adminCampus = campus(1L);
        Campus otherCampus = campus(2L);
        User rootAdmin = user(1L, adminCampus, UserRole.ROOT_ADMIN);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> adminAccessService.requireSameCampus(rootAdmin, otherCampus.getCampusId())
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
    }

    @Test
    void getApprovedRootAdmin_allows_root_without_campus_admin_approval_row() {
        User rootAdmin = user(1L, campus(1L), UserRole.ROOT_ADMIN);
        when(userRepository.findById(1L)).thenReturn(Optional.of(rootAdmin));

        User actual = adminAccessService.getApprovedRootAdmin(1L);

        assertSame(rootAdmin, actual);
        verifyNoInteractions(campusAdminRepository);
    }

    private Campus campus(Long campusId) {
        Campus campus = new Campus("캠퍼스-" + campusId, null, null, null);
        ReflectionTestUtils.setField(campus, "id", campusId);
        return campus;
    }

    private User user(Long userId, Campus campus, UserRole role) {
        User user = new User(campus, "tester", "tester" + userId + "@airs.test", "hashed-password", "01012345678", role);
        ReflectionTestUtils.setField(user, "id", userId);
        return user;
    }
}
