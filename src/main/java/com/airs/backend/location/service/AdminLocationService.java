package com.airs.backend.location.service;

import com.airs.backend.admin.service.AdminAccessService;
import com.airs.backend.location.dto.admin.AdminBuildingResponse;
import com.airs.backend.location.dto.admin.AdminSpaceResponse;
import com.airs.backend.location.entity.Building;
import com.airs.backend.location.entity.Space;
import com.airs.backend.location.repository.BuildingRepository;
import com.airs.backend.location.repository.SpaceRepository;
import com.airs.backend.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminLocationService {

    private final AdminAccessService adminAccessService;
    private final BuildingRepository buildingRepository;
    private final SpaceRepository spaceRepository;

    public List<AdminBuildingResponse> getBuildings(Long userId, Long campusId) {
        User admin = adminAccessService.getApprovedAdmin(userId);
        validateSameCampus(admin, campusId);

        return buildingRepository.findAllByCampus_IdAndDeletedAtIsNullOrderByNameAsc(campusId)
                .stream()
                .map(this::toBuildingResponse)
                .toList();
    }

    public List<AdminSpaceResponse> getSpaces(Long userId, Long buildingId) {
        User admin = adminAccessService.getApprovedAdmin(userId);
        Building building = buildingRepository.findByIdAndDeletedAtIsNull(buildingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "건물을 찾을 수 없습니다."));

        validateSameCampus(admin, building.getCampus().getCampusId());

        return spaceRepository.findAllByBuilding_IdAndDeletedAtIsNullOrderByCodeAsc(buildingId)
                .stream()
                .map(this::toSpaceResponse)
                .toList();
    }

    private void validateSameCampus(User admin, Long campusId) {
        Long adminCampusId = admin.getCampusId();

        if (adminCampusId == null || !adminCampusId.equals(campusId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 캠퍼스 위치 정보를 조회할 수 없습니다.");
        }
    }

    private AdminBuildingResponse toBuildingResponse(Building building) {
        return new AdminBuildingResponse(
                building.getId(),
                building.getName()
        );
    }

    private AdminSpaceResponse toSpaceResponse(Space space) {
        return new AdminSpaceResponse(
                space.getId(),
                space.getCode(),
                space.getName(),
                space.getFloorLabel(),
                space.getSpaceType()
        );
    }
}
