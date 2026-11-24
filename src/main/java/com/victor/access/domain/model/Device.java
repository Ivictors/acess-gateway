package com.victor.access.domain.model;

import java.time.Instant;
import java.util.UUID;

public class Device {
    private final UUID id;
    private final String serialNumber;
    private final String name;
    private final boolean active;
    private final Instant createdAt;

    public Device(UUID id, String serialNumber, String name, boolean active, Instant createdAt) {
        this.id = id;
        this.serialNumber = serialNumber;
        this.name = name;
        this.active = active;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
