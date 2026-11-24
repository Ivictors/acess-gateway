package com.victor.access.application;

import java.util.UUID;

public class DeviceNotFoundException extends RuntimeException {
    public DeviceNotFoundException(UUID deviceId) {
        super("Device not found: " + deviceId);
    }
}
