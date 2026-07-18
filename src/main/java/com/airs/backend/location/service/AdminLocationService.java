package com.airs.backend.location.service;

import com.airs.backend.admin.service.AdminAccessService;
import com.airs.backend.location.dto.CampusResponse;
import com.airs.backend.location.dto.admin.AdminBuildingResponse;
import com.airs.backend.location.dto.admin.AdminCreateBuildingRequest;
import com.airs.backend.location.dto.admin.AdminCreateCampusRequest;
import com.airs.backend.location.dto.admin.AdminCreateSpaceRequest;
import com.airs.backend.location.dto.admin.AdminSpaceResponse;
import com.airs.backend.location.entity.Building;
import com.airs.backend.location.entity.Campus;
import com.airs.backend.location.entity.Space;
import com.airs.backend.location.entity.SpaceType;
import com.airs.backend.location.repository.BuildingRepository;
import com.airs.backend.location.repository.CampusRepository;
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
    private final CampusRepository campusRepository;
    private final BuildingRepository buildingRepository;
    private final SpaceRepository spaceRepository;

    @Transactional
    public CampusResponse createCampus(Long userId, AdminCreateCampusRequest request) {
        adminAccessService.getApprovedAdmin(userId);

        if (campusRepository.existsByNameAndDeletedAtIsNull(request.getName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 캠퍼스 이름입니다.");
        }

        Campus campus = campusRepository.save(new Campus(
                request.getName(),
                request.getLatitude(),
                request.getLongitude(),
                request.getRadiusMeter()
        ));

        return toCampusResponse(campus);
    }

    public List<AdminBuildingResponse> getBuildings(Long userId, Long campusId) {
        User admin = adminAccessService.getApprovedAdmin(userId);
        Campus campus = campusRepository.findByIdAndDeletedAtIsNull(campusId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "캠퍼스를 찾을 수 없습니다."));

        validateSameCampus(admin, campus.getCampusId());

        return buildingRepository.findAllByCampus_IdAndDeletedAtIsNullOrderByNameAsc(campusId)
                .stream()
                .map(this::toBuildingResponse)
                .toList();
    }

    @Transactional
    public AdminBuildingResponse createBuilding(Long userId, Long campusId, AdminCreateBuildingRequest request) {
        User admin = adminAccessService.getApprovedAdmin(userId);
        Campus campus = campusRepository.findByIdAndDeletedAtIsNull(campusId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "캠퍼스를 찾을 수 없습니다."));

        validateSameCampus(admin, campus.getCampusId());

        if (buildingRepository.existsByCampus_IdAndNameAndDeletedAtIsNull(campusId, request.getName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 건물 이름입니다.");
        }

        Building building = buildingRepository.save(new Building(campus, request.getName()));

        return toBuildingResponse(building);
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

    @Transactional
    public AdminSpaceResponse createSpace(Long userId, Long buildingId, AdminCreateSpaceRequest request) {
        User admin = adminAccessService.getApprovedAdmin(userId);
        Building building = buildingRepository.findByIdAndDeletedAtIsNull(buildingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "건물을 찾을 수 없습니다."));

        validateSameCampus(admin, building.getCampus().getCampusId());

        if (spaceRepository.existsByCampus_IdAndCode(building.getCampus().getCampusId(), request.getCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 공간 코드입니다.");
        }

        Space space = spaceRepository.save(new Space(
                building.getCampus(),
                building,
                request.getCode(),
                request.getName(),
                request.getFloorLabel(),
                resolveSpaceType(request.getSpaceType()),
                request.getLatitude(),
                request.getLongitude()
        ));

        return toSpaceResponse(space);
    }

    private void validateSameCampus(User admin, Long campusId) {
        Long adminCampusId = admin.getCampusId();

        if (adminCampusId == null || !adminCampusId.equals(campusId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 캠퍼스 위치 정보를 조회할 수 없습니다.");
        }
    }

    private CampusResponse toCampusResponse(Campus campus) {
        return new CampusResponse(
                campus.getCampusId(),
                campus.getName()
        );
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

    private SpaceType resolveSpaceType(SpaceType spaceType) {
        if (spaceType == null) {
            return SpaceType.OTHER;
        }
        return spaceType;
    }
}
