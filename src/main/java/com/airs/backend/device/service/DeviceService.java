package com.airs.backend.device.service;

import com.airs.backend.device.dto.*;
import com.airs.backend.device.entity.Device;
import com.airs.backend.device.repository.DeviceRepository;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserPreference;
import com.airs.backend.user.repository.UserPreferenceRepository;
import com.airs.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class DeviceService {
    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final UserPreferenceRepository userPreferenceRepository;

    public DeviceRegisterResponse registerDevice(Long userId, DeviceRegisterRequest request) {
        if (deviceRepository.existsById(request.getNodeId())) {
            throw new IllegalArgumentException("이미 등록된 기기입니다.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        UserPreference userPreference = userPreferenceRepository.findById(userId)
                .orElse(null);

        Device device = new Device(
                request.getNodeId(),
                user,
                null,
                null,
                null
        );

        // dirty checking :
        // 조회된 엔티티의 값을 바꾸면
        // @Transactional이 끝날 때 JPA가 변경을 감지해서 DB에 반영
        device.applyDefaultPreferences(userPreference);

        Device savedDevice = deviceRepository.save(device);

        return new DeviceRegisterResponse(
                savedDevice.getNodeId(),
                savedDevice.getUser().getUserId(),
                savedDevice.getPreferredTemperature(),
                savedDevice.getPreferredHumidity(),
                savedDevice.getWifiSsid(),
                savedDevice.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<DeviceSummaryResponse> getMyDevices(Long userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        List<Device> devices = deviceRepository.findAllByUser_UserId(userId);

        return devices.stream()
                .map(device -> new DeviceSummaryResponse(
                        device.getNodeId(),
                        device.getPreferredTemperature(),
                        device.getPreferredHumidity(),
                        device.getWifiSsid(),
                        device.getCreatedAt()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public DeviceDetailResponse getDevice(Long userId, String nodeId) {
        Device device = deviceRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("기기를 찾을 수 없습니다."));

        if (!device.getUser().getUserId().equals(userId)) {
            throw new IllegalArgumentException("해당 기기에 접근할 수 없습니다.");
        }

        return new DeviceDetailResponse(
                device.getNodeId(),
                device.getUser().getUserId(),
                device.getPreferredTemperature(),
                device.getPreferredHumidity(),
                device.getWifiSsid(),
                device.getCreatedAt()
        );
    }

    @Transactional
    public DeviceDetailResponse updateDevice(
            Long userId,
            String nodeId,
            DeviceUpdateRequest request
    ) {
        Device device = deviceRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("기기를 찾을 수 없습니다."));

        if (!device.getUser().getUserId().equals(userId)) {
            throw new IllegalArgumentException("해당 기기에 접근할 수 없습니다.");
        }

        device.updateSettings(
                request.getPreferredTemperature(),
                request.getPreferredHumidity()
        );

        return new DeviceDetailResponse(
                device.getNodeId(),
                device.getUser().getUserId(),
                device.getPreferredTemperature(),
                device.getPreferredHumidity(),
                device.getWifiSsid(),
                device.getCreatedAt()
        );
    }
}
