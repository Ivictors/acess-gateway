package com.victor.access.application;

import com.victor.access.domain.model.Device;
import com.victor.access.domain.repository.DeviceRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GetDeviceUseCase {
    private final DeviceRepository devices;

    public GetDeviceUseCase(DeviceRepository devices) {
        this.devices = devices;
    }

    public Device execute(UUID id) {
        return devices.findById(id).orElseThrow(() -> new DeviceNotFoundException(id));
    }
}
