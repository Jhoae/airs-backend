package com.airs.backend.node.service;

import com.airs.backend.admin.service.AdminAccessService;
import com.airs.backend.alert.entity.Alert;
import com.airs.backend.alert.entity.AlertStatus;
import com.airs.backend.alert.repository.AlertRepository;
import com.airs.backend.location.entity.Campus;
import com.airs.backend.location.entity.Space;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.node.repository.NodeInstallationRepository;
import com.airs.backend.status.repository.NodeStatusSnapshotRepository;
import com.airs.backend.status.repository.SpaceStatusSnapshotRepository;
import com.airs.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminNodeDeletionServiceTest {

    @Mock
    private AdminAccessService adminAccessService;
    @Mock
    private NodeInstallationRepository nodeInstallationRepository;
    @Mock
    private NodeStatusSnapshotRepository nodeStatusSnapshotRepository;
    @Mock
    private SpaceStatusSnapshotRepository spaceStatusSnapshotRepository;
    @Mock
    private AlertRepository alertRepository;

    @InjectMocks
    private AdminNodeDeletionService adminNodeDeletionService;

    @Test
    void deleteNode_resolves_only_active_alerts_for_the_deleted_node() {
        User admin = mock(User.class);
        Campus campus = mock(Campus.class);
        Space space = mock(Space.class);
        NodeInstallation installation = mock(NodeInstallation.class);
        Alert activeAlert = mock(Alert.class);

        when(adminAccessService.getApprovedAdmin(10L)).thenReturn(admin);
        when(admin.getCampusId()).thenReturn(1L);
        when(campus.getCampusId()).thenReturn(1L);
        when(space.getCampus()).thenReturn(campus);
        when(space.getId()).thenReturn(20L);
        when(installation.getSpace()).thenReturn(space);
        when(nodeInstallationRepository.findByNode_IdAndActiveTrue("node_01"))
                .thenReturn(Optional.of(installation));
        when(nodeStatusSnapshotRepository.findByNode_Id("node_01")).thenReturn(Optional.empty());
        when(alertRepository.findAllByNode_IdAndStatusOrderByLastDetectedAtDesc("node_01", AlertStatus.ACTIVE))
                .thenReturn(List.of(activeAlert));
        when(spaceStatusSnapshotRepository.findBySpace_Id(20L)).thenReturn(Optional.empty());

        adminNodeDeletionService.deleteNode(10L, "node_01");

        verify(installation).deactivate();
        ArgumentCaptor<LocalDateTime> resolvedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(activeAlert).resolve(resolvedAt.capture());
        assertThat(resolvedAt.getValue()).isNotNull();
        verify(alertRepository).findAllByNode_IdAndStatusOrderByLastDetectedAtDesc(
                eq("node_01"), eq(AlertStatus.ACTIVE)
        );
    }
}
