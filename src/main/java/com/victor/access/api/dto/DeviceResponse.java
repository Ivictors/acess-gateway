package com.victor.access.api.dto;

import com.victor.access.domain.model.Device;
import java.time.Instant;
import java.util.UUID;

public record DeviceResponse(
        UUID id,
        String serialNumber,
        String name,
        boolean active,
        Instant createdAt
) {
    public static DeviceResponse from(Device device) {
        return new DeviceResponse(
                device.getId(),
                device.getSerialNumber(),
                device.getName(),
                device.isActive(),
                device.getCreatedAt()
        );
    }
}
