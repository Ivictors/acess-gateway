package com.victor.access.api.dto;

import com.victor.access.application.CreateDeviceUseCase;

public record DeviceRegistrationResponse(DeviceResponse device, String apiKey) {
    public static DeviceRegistrationResponse from(CreateDeviceUseCase.Result result) {
        return new DeviceRegistrationResponse(DeviceResponse.from(result.device()), result.apiKey());
    }
}
