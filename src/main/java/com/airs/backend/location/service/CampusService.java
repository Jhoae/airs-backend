package com.airs.backend.location.service;

import com.airs.backend.location.dto.CampusResponse;
import com.airs.backend.location.entity.Campus;
import com.airs.backend.location.repository.CampusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CampusService {

    private final CampusRepository campusRepository;

    public List<CampusResponse> getCampuses() {
        return campusRepository.findAllByDeletedAtIsNullOrderByNameAsc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private CampusResponse toResponse(Campus campus) {
        return new CampusResponse(
                campus.getCampusId(),
                campus.getName()
        );
    }
}
