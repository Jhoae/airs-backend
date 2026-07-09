package com.airs.backend.location.repository;

import com.airs.backend.location.entity.Building;
import com.airs.backend.location.entity.Campus;
import com.airs.backend.location.entity.Space;
import com.airs.backend.location.entity.SpaceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("local")
class LocationRepositoryMySqlTest {

    @Autowired
    private CampusRepository campusRepository;

    @Autowired
    private BuildingRepository buildingRepository;

    @Autowired
    private SpaceRepository spaceRepository;

    @AfterEach
    void cleanUp() {
        spaceRepository.deleteAllInBatch();
        buildingRepository.deleteAllInBatch();
        campusRepository.deleteAllInBatch();
    }

    @Test
    void save_should_insert_campus_building_and_space_into_mysql() {
        Campus campus = campusRepository.saveAndFlush(new Campus(
                "Sogang University",
                new BigDecimal("37.5509440"),
                new BigDecimal("126.9410020"),
                500
        ));
        Building building = buildingRepository.saveAndFlush(new Building(
                campus,
                "Kim Daegeon Hall"
        ));
        Space space = spaceRepository.saveAndFlush(new Space(
                campus,
                building,
                "K301",
                "Room K301",
                "3F",
                SpaceType.CLASSROOM,
                new BigDecimal("37.5512500"),
                new BigDecimal("126.9417500")
        ));

        assertNotNull(campus.getCampusId());
        assertNotNull(building.getId());
        assertNotNull(space.getId());
        assertNotNull(space.getCreatedAt());
        assertTrue(campusRepository.existsByName("Sogang University"));

        List<Building> buildings = buildingRepository.findAllByCampus_Id(campus.getCampusId());
        List<Space> spaces = spaceRepository.findAllByCampus_Id(campus.getCampusId());

        assertEquals(1, buildings.size());
        assertEquals(1, spaces.size());
    }
}
