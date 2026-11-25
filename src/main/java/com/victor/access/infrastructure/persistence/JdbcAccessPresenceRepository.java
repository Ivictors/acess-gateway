package com.victor.access.infrastructure.persistence;

import com.victor.access.domain.model.AccessPresence;
import com.victor.access.domain.repository.AccessPresenceRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccessPresenceRepository implements AccessPresenceRepository {
    private final JdbcClient jdbcClient;

    public JdbcAccessPresenceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public AccessPresence lockOrCreate(String credentialRef) {
        jdbcClient.sql("""
                        INSERT INTO access_presence (credential_ref)
                        VALUES (:credentialRef)
                        ON CONFLICT (credential_ref) DO NOTHING
                        """)
                .param("credentialRef", credentialRef)
                .update();
        return jdbcClient.sql("""
                        SELECT credential_ref, inside, last_occurred_at
                        FROM access_presence
                        WHERE credential_ref = :credentialRef
                        FOR UPDATE
                        """)
                .param("credentialRef", credentialRef)
                .query((rs, rowNum) -> {
                    OffsetDateTime lastOccurredAt = rs.getObject("last_occurred_at", OffsetDateTime.class);
                    return new AccessPresence(rs.getString("credential_ref"), rs.getBoolean("inside"),
                            lastOccurredAt == null ? null : lastOccurredAt.toInstant());
                })
                .single();
    }

    @Override
    public void advance(String credentialRef, boolean inside, Instant occurredAt, UUID eventId) {
        int updated = jdbcClient.sql("""
                        UPDATE access_presence
                        SET inside = :inside, last_occurred_at = :occurredAt, last_event_id = :eventId
                        WHERE credential_ref = :credentialRef
                        """)
                .param("inside", inside)
                .param("occurredAt", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                .param("eventId", eventId)
                .param("credentialRef", credentialRef)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Locked access presence was not updated");
        }
    }
}
