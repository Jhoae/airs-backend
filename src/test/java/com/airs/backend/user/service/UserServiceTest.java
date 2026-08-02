package com.airs.backend.user.service;

import com.airs.backend.user.dto.UserMeResponse;
import com.airs.backend.user.entity.AdminApprovalStatus;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CampusAdminRepository campusAdminRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void getMyInfo_should_reuse_one_campus_admin_query_for_admin_fields() {
        User user = user(1L, UserRole.ADMIN);
        CampusAdmin campusAdmin = new CampusAdmin(null, user, CampusAdminStatus.PENDING);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(campusAdminRepository.findByUser_Id(1L)).thenReturn(Optional.of(campusAdmin));

        UserMeResponse response = userService.getMyInfo(1L);

        assertFalse(response.getAdminApproved());
        assertEquals(AdminApprovalStatus.PENDING, response.getAdminApprovalStatus());
        verify(campusAdminRepository).findByUser_Id(1L);
    }

    @Test
    void getMyInfo_should_not_query_campus_admin_for_user() {
        User user = user(1L, UserRole.USER);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        UserMeResponse response = userService.getMyInfo(1L);

        assertNull(response.getAdminApproved());
        assertEquals(AdminApprovalStatus.NOT_APPLICABLE, response.getAdminApprovalStatus());
        verify(campusAdminRepository, never()).findByUser_Id(1L);
    }

    @Test
    void getMyInfo_should_not_query_campus_admin_for_root_admin() {
        User user = user(1L, UserRole.ROOT_ADMIN);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        UserMeResponse response = userService.getMyInfo(1L);

        assertTrue(response.getAdminApproved());
        assertEquals(AdminApprovalStatus.APPROVED, response.getAdminApprovalStatus());
        verify(campusAdminRepository, never()).findByUser_Id(1L);
    }

    private User user(Long id, UserRole role) {
        User user = new User(null, "tester", "tester@example.com", "hashed-password", "01012345678", role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
