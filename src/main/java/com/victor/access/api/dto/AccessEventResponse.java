package com.victor.access.api.dto;

import com.victor.access.domain.model.AccessEvent;
import com.victor.access.domain.model.EventType;
import com.victor.access.domain.model.EventClassification;
import java.time.Instant;
import java.util.UUID;

public record AccessEventResponse(
        UUID id,
        UUID deviceId,
        String externalEventId,
        EventType eventType,
        String credentialRef,
        Instant occurredAt,
        Instant receivedAt,
        EventClassification classification
) {
    public static AccessEventResponse from(AccessEvent event) {
        return new AccessEventResponse(
                event.getId(),
                event.getDeviceId(),
                event.getExternalEventId(),
                event.getEventType(),
                event.getCredentialRef(),
                event.getOccurredAt(),
                event.getReceivedAt(),
                event.getClassification()
        );
    }
}
