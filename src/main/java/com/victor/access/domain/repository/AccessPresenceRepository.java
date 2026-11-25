package com.victor.access.domain.repository;

import com.victor.access.domain.model.AccessPresence;
import java.time.Instant;
import java.util.UUID;

public interface AccessPresenceRepository {
    AccessPresence lockOrCreate(String credentialRef);

    void advance(String credentialRef, boolean inside, Instant occurredAt, UUID eventId);
}
