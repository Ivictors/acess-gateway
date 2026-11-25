package com.victor.access.infrastructure.persistence;

import com.victor.access.domain.model.AccessEvent;
import com.victor.access.domain.model.EventType;
import com.victor.access.domain.model.EventClassification;
import com.victor.access.domain.repository.AccessEventRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccessEventRepository implements AccessEventRepository {
    private static final RowMapper<AccessEvent> EVENT_ROW_MAPPER = JdbcAccessEventRepository::mapEvent;

    private final JdbcClient jdbcClient;

    public JdbcAccessEventRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<AccessEvent> createIfAbsent(
            UUID id,
            UUID deviceId,
            String externalEventId,
            EventType eventType,
            String credentialRef,
            Instant occurredAt
    ) {
        return jdbcClient.sql("""
                        INSERT INTO access_event (
                            id, device_id, external_event_id, event_type, credential_ref, occurred_at
                        )
                        VALUES (
                            :id, :deviceId, :externalEventId, :eventType, :credentialRef, :occurredAt
                        )
                        ON CONFLICT (device_id, external_event_id) DO NOTHING
                        RETURNING id, device_id, external_event_id, event_type,
                                  credential_ref, occurred_at, received_at, classification
                        """)
                .param("id", id)
                .param("deviceId", deviceId)
                .param("externalEventId", externalEventId)
                .param("eventType", eventType.name())
                .param("credentialRef", credentialRef)
                .param("occurredAt", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                .query(EVENT_ROW_MAPPER)
                .optional();
    }

    @Override
    public Optional<AccessEvent> findByDeviceIdAndExternalEventId(UUID deviceId, String externalEventId) {
        return jdbcClient.sql("""
                        SELECT id, device_id, external_event_id, event_type,
                               credential_ref, occurred_at, received_at, classification
                        FROM access_event
                        WHERE device_id = :deviceId AND external_event_id = :externalEventId
                        """)
                .param("deviceId", deviceId)
                .param("externalEventId", externalEventId)
                .query(EVENT_ROW_MAPPER)
                .optional();
    }

    @Override
    public void classify(UUID eventId, EventClassification classification) {
        int updated = jdbcClient.sql("UPDATE access_event SET classification = :classification WHERE id = :eventId")
                .param("classification", classification.name())
                .param("eventId", eventId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Created access event was not classified");
        }
    }

    @Override
    public List<AccessEvent> findByDeviceId(UUID deviceId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }

        return jdbcClient.sql("""
                        SELECT id, device_id, external_event_id, event_type,
                               credential_ref, occurred_at, received_at, classification
                        FROM access_event
                        WHERE device_id = :deviceId
                        ORDER BY occurred_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("deviceId", deviceId)
                .param("limit", limit)
                .query(EVENT_ROW_MAPPER)
                .list();
    }

    private static AccessEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AccessEvent(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("device_id", UUID.class),
                resultSet.getString("external_event_id"),
                EventType.valueOf(resultSet.getString("event_type")),
                resultSet.getString("credential_ref"),
                resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("received_at", OffsetDateTime.class).toInstant(),
                EventClassification.valueOf(resultSet.getString("classification"))
        );
    }
}
