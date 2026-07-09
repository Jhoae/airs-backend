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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminApprovalServiceTest {

    @Mock
    private AdminAccessService adminAccessService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CampusAdminRepository campusAdminRepository;

    @InjectMocks
    private AdminApprovalService adminApprovalService;

    @Test
    void approveAdmin_should_approve_admin_in_same_campus() {
        Campus campus = campus(1L);
        User rootAdmin = user(1L, campus, "root@example.com", UserRole.ROOT_ADMIN);
        User targetAdmin = user(2L, campus, "admin@example.com", UserRole.ADMIN);
        CampusAdmin campusAdmin = new CampusAdmin(campus, targetAdmin, CampusAdminStatus.PENDING);

        when(adminAccessService.getApprovedRootAdmin(1L)).thenReturn(rootAdmin);
        when(userRepository.findById(2L)).thenReturn(Optional.of(targetAdmin));
        when(campusAdminRepository.findByUser_Id(2L)).thenReturn(Optional.of(campusAdmin));

        var response = adminApprovalService.approveAdmin(1L, 2L);

        assertTrue(campusAdmin.isApproved());
        assertEquals(2L, response.getUserId());
        assertEquals(1L, response.getCampusId());
        assertEquals(UserRole.ADMIN, response.getRole());
        assertTrue(response.isAdminApproved());
        assertEquals(CampusAdminStatus.APPROVED, response.getAdminStatus());
    }

    @Test
    void approveAdmin_should_reject_user_role() {
        Campus campus = campus(1L);
        User rootAdmin = user(1L, campus, "root@example.com", UserRole.ROOT_ADMIN);
        User targetUser = user(2L, campus, "user@example.com", UserRole.USER);

        when(adminAccessService.getApprovedRootAdmin(1L)).thenReturn(rootAdmin);
        when(userRepository.findById(2L)).thenReturn(Optional.of(targetUser));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> adminApprovalService.approveAdmin(1L, 2L)
        );

        assertEquals(400, exception.getStatusCode().value());
    }

    @Test
    void approveAdmin_should_reject_admin_in_other_campus() {
        Campus rootCampus = campus(1L);
        Campus otherCampus = campus(2L);
        User rootAdmin = user(1L, rootCampus, "root@example.com", UserRole.ROOT_ADMIN);
        User targetAdmin = user(2L, otherCampus, "admin@example.com", UserRole.ADMIN);

        when(adminAccessService.getApprovedRootAdmin(1L)).thenReturn(rootAdmin);
        when(userRepository.findById(2L)).thenReturn(Optional.of(targetAdmin));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> adminApprovalService.approveAdmin(1L, 2L)
        );

        assertEquals(403, exception.getStatusCode().value());
    }

    @Test
    void approveAdmin_should_return_ok_when_admin_is_already_approved() {
        Campus campus = campus(1L);
        User rootAdmin = user(1L, campus, "root@example.com", UserRole.ROOT_ADMIN);
        User targetAdmin = user(2L, campus, "admin@example.com", UserRole.ADMIN);
        CampusAdmin campusAdmin = new CampusAdmin(campus, targetAdmin, CampusAdminStatus.APPROVED);

        when(adminAccessService.getApprovedRootAdmin(1L)).thenReturn(rootAdmin);
        when(userRepository.findById(2L)).thenReturn(Optional.of(targetAdmin));
        when(campusAdminRepository.findByUser_Id(2L)).thenReturn(Optional.of(campusAdmin));

        var response = adminApprovalService.approveAdmin(1L, 2L);

        verify(campusAdminRepository).findByUser_Id(2L);
        assertTrue(response.isAdminApproved());
    }

    @Test
    void rejectAdmin_should_reject_pending_admin_in_same_campus() {
        Campus campus = campus(1L);
        User rootAdmin = user(1L, campus, "root@example.com", UserRole.ROOT_ADMIN);
        User targetAdmin = user(2L, campus, "admin@example.com", UserRole.ADMIN);
        CampusAdmin campusAdmin = new CampusAdmin(campus, targetAdmin, CampusAdminStatus.PENDING);

        when(adminAccessService.getApprovedRootAdmin(1L)).thenReturn(rootAdmin);
        when(userRepository.findById(2L)).thenReturn(Optional.of(targetAdmin));
        when(campusAdminRepository.findByUser_Id(2L)).thenReturn(Optional.of(campusAdmin));

        var response = adminApprovalService.rejectAdmin(1L, 2L);

        assertFalse(campusAdmin.isApproved());
        assertEquals(CampusAdminStatus.REJECTED, campusAdmin.getStatus());
        assertEquals(CampusAdminStatus.REJECTED, response.getAdminStatus());
    }

    @Test
    void getPendingAdmins_should_return_pending_admins_in_root_admin_campus() {
        Campus campus = campus(1L);
        User rootAdmin = user(1L, campus, "root@example.com", UserRole.ROOT_ADMIN);
        User firstPendingAdmin = user(2L, campus, "first@example.com", UserRole.ADMIN);
        User secondPendingAdmin = user(3L, campus, "second@example.com", UserRole.ADMIN);

        when(adminAccessService.getApprovedRootAdmin(1L)).thenReturn(rootAdmin);
        when(campusAdminRepository.findAdminRequestsByCampusIdAndStatus(1L, CampusAdminStatus.PENDING, UserRole.ADMIN))
                .thenReturn(List.of(
                        new CampusAdmin(campus, firstPendingAdmin, CampusAdminStatus.PENDING),
                        new CampusAdmin(campus, secondPendingAdmin, CampusAdminStatus.PENDING)
                ));

        var responses = adminApprovalService.getPendingAdmins(1L);

        assertEquals(2, responses.size());
        assertEquals(2L, responses.get(0).getUserId());
        assertEquals("first@example.com", responses.get(0).getEmail());
        assertEquals("01012345678", responses.get(0).getPhone());
        assertEquals(UserRole.ADMIN, responses.get(0).getRole());
        assertFalse(responses.get(0).isAdminApproved());
        assertEquals(CampusAdminStatus.PENDING, responses.get(0).getAdminStatus());
    }

    private Campus campus(Long id) {
        Campus campus = new Campus("campus-" + id, null, null, null);
        ReflectionTestUtils.setField(campus, "id", id);
        return campus;
    }

    private User user(Long id, Campus campus, String email, UserRole role) {
        User user = new User(campus, "tester", email, "hashed-password", "01012345678", role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
