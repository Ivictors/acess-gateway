package com.victor.access.application;

import com.victor.access.domain.model.Device;
import com.victor.access.domain.repository.DeviceRepository;
import com.victor.access.domain.repository.DeviceCredentialRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreateDeviceUseCase {
    private final DeviceRepository devices;
    private final DeviceCredentialRepository credentials;
    private final DeviceTokenService tokens;

    public CreateDeviceUseCase(DeviceRepository devices, DeviceCredentialRepository credentials,
                               DeviceTokenService tokens) {
        this.devices = devices;
        this.credentials = credentials;
        this.tokens = tokens;
    }

    @Transactional
    public Result execute(String serialNumber, String name) {
        Device device = devices.createIfAbsent(UUID.randomUUID(), serialNumber, name)
                .orElseThrow(() -> new ConflictException("Device serial number already exists"));
        DeviceTokenService.IssuedToken token = tokens.issue();
        credentials.create(device.getId(), token.hash());
        return new Result(device, token.value());
    }

    public record Result(Device device, String apiKey) {
    }
}
