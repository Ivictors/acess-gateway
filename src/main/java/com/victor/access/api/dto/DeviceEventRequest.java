package com.victor.access.api.dto;

import com.victor.access.domain.model.EventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public record DeviceEventRequest(
        @NotNull UUID deviceId,
        @NotBlank @Size(max = 100) String externalEventId,
        @NotNull EventType eventType,
        @NotBlank @Size(max = 100) String credentialRef,
        @NotNull Instant occurredAt
) {
}
