package com.victor.access.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateDeviceRequest(
        @NotBlank @Size(max = 100) String serialNumber,
        @NotBlank @Size(max = 150) String name
) {
}
