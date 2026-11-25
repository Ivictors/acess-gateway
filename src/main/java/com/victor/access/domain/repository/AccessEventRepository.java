package com.victor.access.domain.repository;

import com.victor.access.domain.model.AccessEvent;
import com.victor.access.domain.model.EventType;
import com.victor.access.domain.model.EventClassification;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccessEventRepository {
    Optional<AccessEvent> createIfAbsent(
            UUID id,
            UUID deviceId,
            String externalEventId,
            EventType eventType,
            String credentialRef,
            Instant occurredAt
    );

    Optional<AccessEvent> findByDeviceIdAndExternalEventId(UUID deviceId, String externalEventId);

    void classify(UUID eventId, EventClassification classification);

    List<AccessEvent> findByDeviceId(UUID deviceId, int limit);
}
