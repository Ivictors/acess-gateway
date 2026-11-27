package com.victor.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.victor.access.application.ConflictException;
import com.victor.access.application.CreateDeviceUseCase;
import com.victor.access.application.IngestDeviceEventUseCase;
import com.victor.access.domain.model.Device;
import com.victor.access.domain.model.EventClassification;
import com.victor.access.domain.model.EventType;
import com.victor.access.domain.repository.AccessEventRepository;
import com.victor.access.infrastructure.persistence.JdbcCredentialRefRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@AutoConfigureMockMvc
@Transactional
class AccessApiIntegrationTests {
    private static final String ADMIN_TOKEN = "admin-token-for-integration-tests-only-123456789";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private CreateDeviceUseCase createDevice;

    @Autowired
    private IngestDeviceEventUseCase ingestEvent;

    @Autowired
    private AccessEventRepository events;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private JdbcCredentialRefRepository refs;

    private void authorize(UUID deviceId, String ref) {
        refs.create(ref);
        refs.assign(deviceId, ref);
    }

    @Test
    void classifiesTransitionsAcrossDevicesInOneArea() throws Exception {
        var first = createDevice.execute("SN-presence-a", "Entrance");
        var second = createDevice.execute("SN-presence-b", "Side entrance");
        String credential = "card-presence";
        authorize(first.device().getId(), credential);
        authorize(second.device().getId(), credential);

        var entry = ingestEvent.execute(new IngestDeviceEventUseCase.Command(
                first.device().getId(), "entry-1", EventType.ACCESS_GRANTED, credential,
                Instant.parse("2026-01-01T10:00:00Z")));
        var denied = ingestEvent.execute(new IngestDeviceEventUseCase.Command(
                second.device().getId(), "denied-1", EventType.ACCESS_DENIED, credential,
                Instant.parse("2026-01-01T10:01:00Z")));
        var secondEntry = ingestEvent.execute(new IngestDeviceEventUseCase.Command(
                second.device().getId(), "entry-2", EventType.ACCESS_GRANTED, credential,
                Instant.parse("2026-01-01T10:02:00Z")));
        var exit = ingestEvent.execute(new IngestDeviceEventUseCase.Command(
                first.device().getId(), "exit-1", EventType.EXIT_REGISTERED, credential,
                Instant.parse("2026-01-01T10:03:00Z")));
        var secondExit = ingestEvent.execute(new IngestDeviceEventUseCase.Command(
                second.device().getId(), "exit-2", EventType.EXIT_REGISTERED, credential,
                Instant.parse("2026-01-01T10:04:00Z")));

        assertEquals(EventClassification.NORMAL, entry.event().getClassification());
        assertEquals(EventClassification.NORMAL, denied.event().getClassification());
        assertEquals(EventClassification.ENTRY_WHILE_INSIDE, secondEntry.event().getClassification());
        assertEquals(EventClassification.NORMAL, exit.event().getClassification());
        assertEquals(EventClassification.EXIT_WHILE_OUTSIDE, secondExit.event().getClassification());
        assertEquals(EventClassification.ENTRY_WHILE_INSIDE,
                ingestEvent.execute(new IngestDeviceEventUseCase.Command(
                        second.device().getId(), "entry-2", EventType.ACCESS_GRANTED, credential,
                        Instant.parse("2026-01-01T10:02:00Z"))).event().getClassification());

        mvc.perform(get("/devices/{id}/events", second.device().getId())
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].classification").value("ENTRY_WHILE_INSIDE"));
    }

    @Test
    void storesLateTransitionsWithoutChangingCurrentPresence() {
        UUID deviceId = createDevice.execute("SN-late-events", "Entrance").device().getId();
        String credential = "card-late";
        authorize(deviceId, credential);
        ingestEvent.execute(new IngestDeviceEventUseCase.Command(deviceId, "entry-new",
                EventType.ACCESS_GRANTED, credential, Instant.parse("2026-01-01T10:10:00Z")));
        var lateExit = ingestEvent.execute(new IngestDeviceEventUseCase.Command(deviceId, "exit-late",
                EventType.EXIT_REGISTERED, credential, Instant.parse("2026-01-01T10:00:00Z")));
        var currentExit = ingestEvent.execute(new IngestDeviceEventUseCase.Command(deviceId, "exit-current",
                EventType.EXIT_REGISTERED, credential, Instant.parse("2026-01-01T10:20:00Z")));

        assertEquals(EventClassification.OUT_OF_ORDER, lateExit.event().getClassification());
        assertEquals(EventClassification.NORMAL, currentExit.event().getClassification());
        assertEquals(EventClassification.OUT_OF_ORDER,
                events.findByDeviceIdAndExternalEventId(deviceId, "exit-late")
                        .orElseThrow().getClassification());
        assertEquals(false, jdbcClient.sql("SELECT inside FROM access_presence WHERE credential_ref = :ref")
                .param("ref", credential).query(Boolean.class).single());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentDistinctEntriesAreClassifiedConsistently() throws Exception {
        Device device = createDevice.execute("SN-presence-concurrent", "Entrance").device();
        UUID deviceId = device.getId();
        CountDownLatch start = new CountDownLatch(1);
        authorize(deviceId, "card-concurrent-presence");
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> {
                start.await();
                return ingestEvent.execute(new IngestDeviceEventUseCase.Command(deviceId, "entry-a",
                        EventType.ACCESS_GRANTED, "card-concurrent-presence",
                        Instant.parse("2026-01-01T10:00:00Z")));
            });
            var second = workers.submit(() -> {
                start.await();
                return ingestEvent.execute(new IngestDeviceEventUseCase.Command(deviceId, "entry-b",
                        EventType.ACCESS_GRANTED, "card-concurrent-presence",
                        Instant.parse("2026-01-01T10:00:00Z")));
            });
            start.countDown();

            var classifications = java.util.Set.of(first.get(15, TimeUnit.SECONDS).event().getClassification(),
                    second.get(15, TimeUnit.SECONDS).event().getClassification());
            assertEquals(java.util.Set.of(EventClassification.NORMAL, EventClassification.OUT_OF_ORDER),
                    classifications);
            assertEquals(2, events.findByDeviceId(deviceId, 10).size());
            assertTrue(jdbcClient.sql("SELECT inside FROM access_presence WHERE credential_ref = :ref")
                    .param("ref", "card-concurrent-presence").query(Boolean.class).single());
        } finally {
            workers.shutdownNow();
            jdbcClient.sql("DELETE FROM access_event WHERE device_id = :deviceId")
                    .param("deviceId", deviceId).update();
            jdbcClient.sql("DELETE FROM device WHERE id = :id")
                    .param("id", deviceId).update();
        }
    }

    @Test
    void rejectsMissingOccurredAtBeforeIngestingEvent() throws Exception {
        IllegalArgumentException missingCommand = assertThrows(IllegalArgumentException.class,
                () -> ingestEvent.execute(null));
        assertEquals("command is required", missingCommand.getMessage());

        var registration = createDevice.execute("SN-missing-time", "Entrance");
        UUID deviceId = registration.device().getId();
        var command = new IngestDeviceEventUseCase.Command(
                deviceId, "event-missing-time", EventType.ACCESS_GRANTED, "card-1", null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> ingestEvent.execute(command));
        assertEquals("occurredAt is required", exception.getMessage());
        assertEquals(0, events.findByDeviceId(deviceId, 10).size());

        String request = """
                {"deviceId":"%s","externalEventId":"event-missing-time",
                 "eventType":"ACCESS_GRANTED","credentialRef":"card-1"}
                """.formatted(deviceId);
        mvc.perform(post("/device-events")
                        .header("Authorization", "Bearer " + registration.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest());
        assertEquals(0, events.findByDeviceId(deviceId, 10).size());
    }

    @Test
    void repeatedEventReturnsOriginalAndDifferentPayloadConflicts() {
        Device device = createDevice.execute("SN-idempotency", "Entrance").device();
        UUID deviceId = device.getId();
        Instant occurredAt = Instant.parse("2026-01-01T10:00:00.123456789Z");
        authorize(deviceId, "card-1");
        var command = new IngestDeviceEventUseCase.Command(
                deviceId, "event-42", EventType.ACCESS_GRANTED, "card-1", occurredAt);

        var first = ingestEvent.execute(command);
        var retry = ingestEvent.execute(command);

        assertTrue(first.created());
        assertFalse(retry.created());
        assertEquals(first.event().getId(), retry.event().getId());
        assertEquals(first.event().getReceivedAt(), retry.event().getReceivedAt());
        assertEquals(Instant.parse("2026-01-01T10:00:00.123456Z"), retry.event().getOccurredAt());
        assertEquals(1, events.findByDeviceId(deviceId, 10).size());

        var changed = new IngestDeviceEventUseCase.Command(
                deviceId, "event-42", EventType.ACCESS_DENIED, "card-1", occurredAt);
        assertThrows(ConflictException.class, () -> ingestEvent.execute(changed));
        assertEquals(1, events.findByDeviceId(deviceId, 10).size());
    }

    @Test
    void refusesNewEventsForInactiveDevice() {
        Device device = createDevice.execute("SN-inactive", "Side entrance").device();
        authorize(device.getId(), "card-1");
        var original = new IngestDeviceEventUseCase.Command(
                device.getId(), "event-before-deactivation", EventType.ACCESS_GRANTED,
                "card-1", Instant.parse("2026-01-01T10:00:00Z"));
        ingestEvent.execute(original);
        jdbcClient.sql("UPDATE device SET active = FALSE WHERE id = :id")
                .param("id", device.getId()).update();

        var command = new IngestDeviceEventUseCase.Command(
                device.getId(), "event-1", EventType.ACCESS_GRANTED, "card-1", Instant.now());
        assertThrows(ConflictException.class, () -> ingestEvent.execute(command));
        assertThrows(ConflictException.class, () -> ingestEvent.execute(original));
        assertEquals(1, events.findByDeviceId(device.getId(), 10).size());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousRetriesCreateOnlyOneEvent() throws Exception {
        Device device = createDevice.execute("SN-concurrent", "Entrance").device();
        authorize(device.getId(), "card-1");
        var command = new IngestDeviceEventUseCase.Command(
                device.getId(), "event-concurrent", EventType.ACCESS_GRANTED,
                "card-1", Instant.parse("2026-01-01T10:00:00Z"));
        CountDownLatch start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> {
                start.await();
                return ingestEvent.execute(command);
            });
            var second = workers.submit(() -> {
                start.await();
                return ingestEvent.execute(command);
            });
            start.countDown();

            var firstResult = first.get(15, TimeUnit.SECONDS);
            var secondResult = second.get(15, TimeUnit.SECONDS);
            assertTrue(firstResult.created() != secondResult.created());
            assertEquals(firstResult.event().getId(), secondResult.event().getId());
            assertEquals(1, events.findByDeviceId(device.getId(), 10).size());
        } finally {
            workers.shutdownNow();
            jdbcClient.sql("DELETE FROM access_event WHERE device_id = :deviceId")
                    .param("deviceId", device.getId()).update();
            jdbcClient.sql("DELETE FROM device WHERE id = :id")
                    .param("id", device.getId()).update();
        }
    }

    @Test
    void exposesDeviceAndEventLifecycleOverHttp() throws Exception {
        String deviceJson = """
                {"serialNumber":"SN-api","name":"Entrance"}
                """;
        String deviceBody = mvc.perform(post("/devices")
                        .header("Authorization", "Bearer " + ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(deviceJson))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.device.active").value(true))
                .andExpect(jsonPath("$.device.createdAt").exists())
                .andExpect(jsonPath("$.apiKey").exists())
                .andReturn().getResponse().getContentAsString();
        UUID deviceId = UUID.fromString(JsonPath.read(deviceBody, "$.device.id"));
        String deviceToken = JsonPath.read(deviceBody, "$.apiKey");
        authorize(deviceId, "card-1");

        mvc.perform(get("/devices/{id}", deviceId).header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serialNumber").value("SN-api"));

        String eventJson = """
                {"deviceId":"%s","externalEventId":"event-api","eventType":"ACCESS_GRANTED",
                 "credentialRef":"card-1","occurredAt":"2026-01-01T10:00:00Z"}
                """.formatted(deviceId);
        mvc.perform(post("/device-events")
                        .header("Authorization", "Bearer " + deviceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(eventJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalEventId").value("event-api"));
        mvc.perform(post("/device-events")
                        .header("Authorization", "Bearer " + deviceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(eventJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalEventId").value("event-api"));
        mvc.perform(post("/device-events")
                        .header("Authorization", "Bearer " + deviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson.replace("ACCESS_GRANTED", "ACCESS_DENIED")))
                .andExpect(status().isConflict());
        mvc.perform(get("/devices/{id}/events", deviceId)
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void returnsValidationNotFoundAndConflictStatuses() throws Exception {
        mvc.perform(post("/devices").header("Authorization", "Bearer " + ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNumber\":\"\",\"name\":\"Entrance\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/devices/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isNotFound());

        Device device = createDevice.execute("SN-query", "Side entrance").device();
        mvc.perform(get("/devices/{id}/events", device.getId()).param("limit", "101")
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/devices/{id}/events", UUID.randomUUID())
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void returnsConflictForDuplicateDeviceSerial() throws Exception {
        Device device = createDevice.execute("SN-conflict", "Entrance").device();
        try {
            mvc.perform(post("/devices").header("Authorization", "Bearer " + ADMIN_TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"serialNumber\":\"SN-conflict\",\"name\":\"Other\"}"))
                    .andExpect(status().isConflict());
        } finally {
            jdbcClient.sql("DELETE FROM device WHERE id = :id").param("id", device.getId()).update();
        }
    }
}
