package com.victor.access.domain.repository;

import com.victor.access.domain.model.Device;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository {
    Device create(UUID id, String serialNumber, String name);

    Optional<Device> findById(UUID id);
}
