package com.victor.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.victor.access.application.CreateDeviceUseCase;
import com.victor.access.application.IngestDeviceEventUseCase;
import com.victor.access.application.InvalidEventTimeException;
import com.victor.access.domain.model.EventType;
import com.victor.access.infrastructure.persistence.JdbcCredentialRefRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import({PostgresTestConfiguration.class, FutureEventTimeIntegrationTests.FixedClockConfiguration.class})
@AutoConfigureMockMvc
@Transactional
class FutureEventTimeIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");

    @Autowired
    private CreateDeviceUseCase createDevice;

    @Autowired
    private IngestDeviceEventUseCase ingestEvent;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcCredentialRefRepository refs;

    @Test
    void acceptsBoundaryAndRejectsLaterEventWithoutChangingPresence() throws Exception {
        var registration = createDevice.execute("SN-time-boundary", "Entrance");
        UUID deviceId = registration.device().getId();
        refs.create("card-at-boundary");
        refs.assign(deviceId, "card-at-boundary");
        Instant boundary = NOW.plusSeconds(300);
        Instant beyondBoundary = boundary.plusNanos(1_000);

        var accepted = ingestEvent.execute(new IngestDeviceEventUseCase.Command(
                deviceId, "event-at-boundary", EventType.ACCESS_GRANTED, "card-at-boundary", boundary));
        assertEquals(boundary, accepted.event().getOccurredAt());

        assertThrows(InvalidEventTimeException.class, () -> ingestEvent.execute(
                new IngestDeviceEventUseCase.Command(deviceId, "event-too-far", EventType.ACCESS_GRANTED,
                        "card-too-far", beyondBoundary)));
        mvc.perform(post("/device-events")
                        .header("Authorization", "Bearer " + registration.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(deviceId, "event-http-too-far", beyondBoundary)))
                .andExpect(status().isBadRequest());

        assertEquals(1, jdbcClient.sql("SELECT COUNT(*) FROM access_event WHERE device_id = :id")
                .param("id", deviceId).query(Integer.class).single());
        assertEquals(0, jdbcClient.sql("SELECT COUNT(*) FROM access_presence WHERE credential_ref = 'card-too-far'")
                .query(Integer.class).single());
        assertEquals(0, jdbcClient.sql("SELECT COUNT(*) FROM access_presence WHERE credential_ref = 'card-existing'")
                .query(Integer.class).single());
        assertEquals(1, jdbcClient.sql("SELECT COUNT(*) FROM access_presence WHERE credential_ref = 'card-at-boundary'")
                .query(Integer.class).single());
    }

    @Test
    void replaysPreviouslyStoredFutureEventButRejectsConflictingPayload() throws Exception {
        var registration = createDevice.execute("SN-time-replay", "Entrance");
        UUID deviceId = registration.device().getId();
        UUID eventId = UUID.randomUUID();
        Instant futureTime = NOW.plusSeconds(3600);
        jdbcClient.sql("""
                INSERT INTO access_event (id, device_id, external_event_id, event_type,
                                          credential_ref, occurred_at, classification)
                VALUES (:id, :deviceId, 'old-future-event', 'ACCESS_GRANTED',
                        'card-existing', :occurredAt, 'NORMAL')
                """)
                .param("id", eventId)
                .param("deviceId", deviceId)
                .param("occurredAt", futureTime.atOffset(ZoneOffset.UTC))
                .update();

        mvc.perform(post("/device-events")
                        .header("Authorization", "Bearer " + registration.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(deviceId, "old-future-event", futureTime)))
                .andExpect(status().isOk());
        mvc.perform(post("/device-events")
                        .header("Authorization", "Bearer " + registration.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(deviceId, "old-future-event", futureTime)
                                .replace("ACCESS_GRANTED", "ACCESS_DENIED")))
                .andExpect(status().isConflict());
        assertEquals(1, jdbcClient.sql("SELECT COUNT(*) FROM access_event WHERE device_id = :id")
                .param("id", deviceId).query(Integer.class).single());
        assertEquals(0, jdbcClient.sql("SELECT COUNT(*) FROM access_presence WHERE credential_ref = 'card-existing'")
                .query(Integer.class).single());
    }

    private static String eventJson(UUID deviceId, String eventId, Instant occurredAt) {
        return """
                {"deviceId":"%s","externalEventId":"%s","eventType":"ACCESS_GRANTED",
                 "credentialRef":"card-existing","occurredAt":"%s"}
                """.formatted(deviceId, eventId, occurredAt);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
