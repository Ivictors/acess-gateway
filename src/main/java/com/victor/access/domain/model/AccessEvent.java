package com.victor.access.domain.model;

import java.time.Instant;
import java.util.UUID;

public class AccessEvent {
    private final UUID id;
    private final UUID deviceId;
    private final String externalEventId;
    private final EventType eventType;
    private final String credentialRef;
    private final Instant occurredAt;
    private final Instant receivedAt;

    public AccessEvent(
            UUID id,
            UUID deviceId,
            String externalEventId,
            EventType eventType,
            String credentialRef,
            Instant occurredAt,
            Instant receivedAt
    ) {
        this.id = id;
        this.deviceId = deviceId;
        this.externalEventId = externalEventId;
        this.eventType = eventType;
        this.credentialRef = credentialRef;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public String getExternalEventId() {
        return externalEventId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public String getCredentialRef() {
        return credentialRef;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
