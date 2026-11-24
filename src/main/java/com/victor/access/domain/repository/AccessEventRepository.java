package com.victor.access.domain.repository;

import com.victor.access.domain.model.AccessEvent;
import com.victor.access.domain.model.EventType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AccessEventRepository {
    AccessEvent create(
            UUID id,
            UUID deviceId,
            String externalEventId,
            EventType eventType,
            String credentialRef,
            Instant occurredAt
    );

    List<AccessEvent> findByDeviceId(UUID deviceId, int limit);
}
