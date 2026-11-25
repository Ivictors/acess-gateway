package com.victor.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class AccessPresenceMigrationIntegrationTests {
    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void backfillsLatestTransitionAndLeavesHistoricalEventsUnclassified() throws Exception {
        String schema = "presence_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("2"))
                    .load().migrate();

            UUID deviceId = UUID.randomUUID();
            UUID exitId = UUID.randomUUID();
            jdbcClient.sql("INSERT INTO " + schema + ".device (id, serial_number, name) "
                            + "VALUES (:id, 'SN-legacy', 'Legacy reader')")
                    .param("id", deviceId).update();
            insertEvent(schema, deviceId, UUID.randomUUID(), "entry", "ACCESS_GRANTED",
                    "card-legacy", "2026-01-01T10:00:00Z");
            insertEvent(schema, deviceId, exitId, "exit", "EXIT_REGISTERED",
                    "card-legacy", "2026-01-01T11:00:00Z");
            insertEvent(schema, deviceId, UUID.randomUUID(), "denied", "ACCESS_DENIED",
                    "card-legacy", "2026-01-01T12:00:00Z");
            insertEvent(schema, deviceId, UUID.randomUUID(), "other-entry", "ACCESS_GRANTED",
                    "card-other", "2026-01-01T13:00:00Z");

            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load().migrate();

            assertFalse(jdbcClient.sql("SELECT inside FROM " + schema
                            + ".access_presence WHERE credential_ref = 'card-legacy'")
                    .query(Boolean.class).single());
            assertEquals(exitId, jdbcClient.sql("SELECT last_event_id FROM " + schema
                            + ".access_presence WHERE credential_ref = 'card-legacy'")
                    .query(UUID.class).single());
            assertTrue(jdbcClient.sql("SELECT inside FROM " + schema
                            + ".access_presence WHERE credential_ref = 'card-other'")
                    .query(Boolean.class).single());
            assertEquals(4L, jdbcClient.sql("SELECT count(*) FROM " + schema
                            + ".access_event WHERE classification = 'UNCLASSIFIED'")
                    .query(Long.class).single());
        } finally {
            try (Connection connection = dataSource.getConnection();
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private void insertEvent(String schema, UUID deviceId, UUID eventId, String externalId,
                             String type, String credential, String occurredAt) {
        jdbcClient.sql("INSERT INTO " + schema + ".access_event "
                        + "(id, device_id, external_event_id, event_type, credential_ref, occurred_at) "
                        + "VALUES (:id, :deviceId, :externalId, :type, :credential, :occurredAt)")
                .param("id", eventId)
                .param("deviceId", deviceId)
                .param("externalId", externalId)
                .param("type", type)
                .param("credential", credential)
                .param("occurredAt", OffsetDateTime.parse(occurredAt))
                .update();
    }
}
