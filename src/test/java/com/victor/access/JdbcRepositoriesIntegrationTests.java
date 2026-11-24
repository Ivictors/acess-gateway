package com.victor.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.victor.access.domain.model.AccessEvent;
import com.victor.access.domain.model.Device;
import com.victor.access.domain.model.EventType;
import com.victor.access.domain.repository.AccessEventRepository;
import com.victor.access.domain.repository.DeviceRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional
class JdbcRepositoriesIntegrationTests {

    @Autowired
    private DeviceRepository devices;

    @Autowired
    private AccessEventRepository events;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void createsDeviceWithDatabaseDefaultsAndFindsItById() {
        UUID id = UUID.randomUUID();

        Device created = devices.create(id, "SN-001", "Front door");

        assertEquals(id, created.getId());
        assertEquals("SN-001", created.getSerialNumber());
        assertEquals("Front door", created.getName());
        assertTrue(created.isActive());
        assertNotNull(created.getCreatedAt());

        Device found = devices.findById(id).orElseThrow();
        assertEquals(created.getCreatedAt(), found.getCreatedAt());
        assertTrue(devices.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void listsOnlyDeviceEventsInOccurrenceOrderAndAppliesLimit() {
        UUID deviceId = UUID.randomUUID();
        UUID otherDeviceId = UUID.randomUUID();
        devices.create(deviceId, "SN-002", "Side door");
        devices.create(otherDeviceId, "SN-003", "Back door");

        Instant olderTime = Instant.parse("2026-01-01T10:00:00.123456Z");
        Instant newerTime = Instant.parse("2026-01-01T11:00:00.654321Z");
        AccessEvent older = events.create(UUID.randomUUID(), deviceId, "evt-1",
                EventType.ACCESS_GRANTED, "card-1", olderTime);
        AccessEvent newer = events.create(UUID.randomUUID(), deviceId, "evt-2",
                EventType.ACCESS_DENIED, "card-2", newerTime);
        events.create(UUID.randomUUID(), otherDeviceId, "evt-1",
                EventType.EXIT_REGISTERED, "card-3", newerTime);

        assertEquals(olderTime, older.getOccurredAt());
        assertEquals(deviceId, older.getDeviceId());
        assertEquals(EventType.ACCESS_GRANTED, older.getEventType());
        assertEquals("card-1", older.getCredentialRef());
        assertNotNull(older.getReceivedAt());

        List<AccessEvent> found = events.findByDeviceId(deviceId, 1);
        assertEquals(1, found.size());
        assertEquals(newer.getId(), found.getFirst().getId());
        assertEquals(newer.getReceivedAt(), found.getFirst().getReceivedAt());
        List<AccessEvent> allDeviceEvents = events.findByDeviceId(deviceId, 2);
        assertEquals(List.of(newer.getId(), older.getId()),
                allDeviceEvents.stream().map(AccessEvent::getId).toList());
        assertEquals(newerTime, allDeviceEvents.get(0).getOccurredAt());
        assertEquals(olderTime, allDeviceEvents.get(1).getOccurredAt());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void readsDeviceAndEventAfterCommitInSeparateTransaction() {
        UUID deviceId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try {
            transaction.executeWithoutResult(status -> {
                devices.create(deviceId, "SN-005", "Lobby");
                events.create(eventId, deviceId, "evt-committed",
                        EventType.ACCESS_GRANTED, "card-5", Instant.parse("2026-01-01T12:00:00Z"));
            });

            transaction.executeWithoutResult(status -> {
                assertTrue(devices.findById(deviceId).isPresent());
                assertEquals(eventId, events.findByDeviceId(deviceId, 1).getFirst().getId());
            });
        } finally {
            jdbcClient.sql("DELETE FROM access_event WHERE id = :id")
                    .param("id", eventId)
                    .update();
            jdbcClient.sql("DELETE FROM device WHERE id = :id")
                    .param("id", deviceId)
                    .update();
        }
    }

    @Test
    void rejectsDuplicateDeviceSerialNumber() {
        devices.create(UUID.randomUUID(), "SN-duplicate", "First door");

        assertThrows(DuplicateKeyException.class, () ->
                devices.create(UUID.randomUUID(), "SN-duplicate", "Second door"));
    }

    @Test
    void rejectsDuplicateExternalEventIdForSameDevice() {
        UUID deviceId = UUID.randomUUID();
        devices.create(deviceId, "SN-004", "Garage");
        events.create(UUID.randomUUID(), deviceId, "evt-duplicate",
                EventType.ACCESS_GRANTED, "card-1", Instant.now());

        assertThrows(DuplicateKeyException.class, () -> events.create(
                UUID.randomUUID(), deviceId, "evt-duplicate",
                EventType.ACCESS_DENIED, "card-2", Instant.now()));
    }

    @Test
    void rejectsEventForUnknownDevice() {
        assertThrows(DataIntegrityViolationException.class, () -> events.create(
                UUID.randomUUID(), UUID.randomUUID(), "evt-orphan",
                EventType.ACCESS_DENIED, "card-1", Instant.now()));
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> events.findByDeviceId(UUID.randomUUID(), 0));
        assertThrows(IllegalArgumentException.class, () -> events.findByDeviceId(UUID.randomUUID(), -1));
    }
}
