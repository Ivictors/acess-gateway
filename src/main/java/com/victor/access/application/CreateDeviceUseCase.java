package com.victor.access.application;

import com.victor.access.domain.model.Device;
import com.victor.access.domain.repository.DeviceRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CreateDeviceUseCase {
    private final DeviceRepository devices;

    public CreateDeviceUseCase(DeviceRepository devices) {
        this.devices = devices;
    }

    public Device execute(String serialNumber, String name) {
        return devices.createIfAbsent(UUID.randomUUID(), serialNumber, name)
                .orElseThrow(() -> new ConflictException("Device serial number already exists"));
    }
}
