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

import com.victor.access.application.ConflictException;
import com.victor.access.application.CreateDeviceUseCase;
import com.victor.access.application.IngestDeviceEventUseCase;
import com.victor.access.domain.model.Device;
import com.victor.access.domain.model.EventType;
import com.victor.access.domain.repository.AccessEventRepository;
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
@AutoConfigureMockMvc(addFilters = false)
@Transactional
class AccessApiIntegrationTests {
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

    @Test
    void repeatedEventReturnsOriginalAndDifferentPayloadConflicts() {
        Device device = createDevice.execute("SN-idempotency", "Entrance");
        UUID deviceId = device.getId();
        Instant occurredAt = Instant.parse("2026-01-01T10:00:00.123456789Z");
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
        Device device = createDevice.execute("SN-inactive", "Side entrance");
        var original = new IngestDeviceEventUseCase.Command(
                device.getId(), "event-before-deactivation", EventType.ACCESS_GRANTED,
                "card-1", Instant.parse("2026-01-01T10:00:00Z"));
        var first = ingestEvent.execute(original);
        jdbcClient.sql("UPDATE device SET active = FALSE WHERE id = :id")
                .param("id", device.getId()).update();

        var command = new IngestDeviceEventUseCase.Command(
                device.getId(), "event-1", EventType.ACCESS_GRANTED, "card-1", Instant.now());
        assertThrows(ConflictException.class, () -> ingestEvent.execute(command));
        var retry = ingestEvent.execute(original);
        assertFalse(retry.created());
        assertEquals(first.event().getId(), retry.event().getId());
        assertEquals(1, events.findByDeviceId(device.getId(), 10).size());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousRetriesCreateOnlyOneEvent() throws Exception {
        Device device = createDevice.execute("SN-concurrent", "Entrance");
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
        String location = mvc.perform(post("/devices")
                        .contentType(MediaType.APPLICATION_JSON).content(deviceJson))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn().getResponse().getHeader("Location");
        UUID deviceId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

        mvc.perform(get("/devices/{id}", deviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serialNumber").value("SN-api"));

        String eventJson = """
                {"deviceId":"%s","externalEventId":"event-api","eventType":"ACCESS_GRANTED",
                 "credentialRef":"card-1","occurredAt":"2026-01-01T10:00:00Z"}
                """.formatted(deviceId);
        mvc.perform(post("/device-events")
                        .contentType(MediaType.APPLICATION_JSON).content(eventJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalEventId").value("event-api"));
        mvc.perform(post("/device-events")
                        .contentType(MediaType.APPLICATION_JSON).content(eventJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalEventId").value("event-api"));
        mvc.perform(post("/device-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson.replace("ACCESS_GRANTED", "ACCESS_DENIED")))
                .andExpect(status().isConflict());
        mvc.perform(get("/devices/{id}/events", deviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void returnsValidationNotFoundAndConflictStatuses() throws Exception {
        mvc.perform(post("/devices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNumber\":\"\",\"name\":\"Entrance\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/devices/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());

        Device device = createDevice.execute("SN-query", "Side entrance");
        mvc.perform(get("/devices/{id}/events", device.getId()).param("limit", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/devices/{id}/events", UUID.randomUUID()))
                .andExpect(status().isNotFound());

    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void returnsConflictForDuplicateDeviceSerial() throws Exception {
        Device device = createDevice.execute("SN-conflict", "Entrance");
        try {
            mvc.perform(post("/devices").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"serialNumber\":\"SN-conflict\",\"name\":\"Other\"}"))
                    .andExpect(status().isConflict());
        } finally {
            jdbcClient.sql("DELETE FROM device WHERE id = :id").param("id", device.getId()).update();
        }
    }
}
