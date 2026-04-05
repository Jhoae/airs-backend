package com.airs.backend.device.repository;

import com.airs.backend.device.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceRepository extends JpaRepository<Device, String> {
    List<Device> findAllByUser_UserId(Long userId);

}
