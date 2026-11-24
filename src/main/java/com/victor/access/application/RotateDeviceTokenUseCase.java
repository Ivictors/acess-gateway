package com.victor.access.application;

import com.victor.access.domain.repository.DeviceCredentialRepository;
import com.victor.access.domain.repository.DeviceRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RotateDeviceTokenUseCase {
    private final DeviceRepository devices;
    private final DeviceCredentialRepository credentials;
    private final DeviceTokenService tokens;

    public RotateDeviceTokenUseCase(DeviceRepository devices, DeviceCredentialRepository credentials,
                                    DeviceTokenService tokens) {
        this.devices = devices;
        this.credentials = credentials;
        this.tokens = tokens;
    }

    @Transactional
    public String execute(UUID deviceId) {
        if (devices.findById(deviceId).isEmpty()) {
            throw new DeviceNotFoundException(deviceId);
        }
        DeviceTokenService.IssuedToken token = tokens.issue();
        credentials.replace(deviceId, token.hash());
        return token.value();
    }
}
