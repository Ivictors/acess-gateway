package com.victor.access.application;

import com.victor.access.domain.model.AccessEvent;
import com.victor.access.domain.model.Device;
import com.victor.access.domain.model.EventType;
import com.victor.access.domain.model.EventClassification;
import com.victor.access.domain.model.PresencePolicy;
import com.victor.access.domain.repository.AccessEventRepository;
import com.victor.access.domain.repository.AccessPresenceRepository;
import com.victor.access.domain.repository.DeviceRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestDeviceEventUseCase {
    private final DeviceRepository devices;
    private final AccessEventRepository events;
    private final AccessPresenceRepository presence;

    public IngestDeviceEventUseCase(DeviceRepository devices, AccessEventRepository events,
                                    AccessPresenceRepository presence) {
        this.devices = devices;
        this.events = events;
        this.presence = presence;
    }

    @Transactional
    public Result execute(Command command) {
        if (command == null) {
            throw new IllegalArgumentException("command is required");
        }
        if (command.occurredAt() == null) {
            throw new IllegalArgumentException("occurredAt is required");
        }
        Device device = devices.findById(command.deviceId())
                .orElseThrow(() -> new DeviceNotFoundException(command.deviceId()));
        Instant occurredAt = command.occurredAt().truncatedTo(ChronoUnit.MICROS);
        if (!device.isActive()) {
            return events.findByDeviceIdAndExternalEventId(command.deviceId(), command.externalEventId())
                    .map(existing -> existingResult(existing, command, occurredAt))
                    .orElseThrow(() -> new ConflictException("Device is inactive"));
        }

        return events.createIfAbsent(
                        UUID.randomUUID(), command.deviceId(), command.externalEventId(),
                        command.eventType(), command.credentialRef(), occurredAt
                )
                .map(event -> new Result(classify(event), true))
                .orElseGet(() -> {
                    AccessEvent existing = events.findByDeviceIdAndExternalEventId(
                                    command.deviceId(), command.externalEventId())
                            .orElseThrow(() -> new IllegalStateException("Conflicting event was not found"));
                    return existingResult(existing, command, occurredAt);
                });
    }

    private AccessEvent classify(AccessEvent event) {
        if (event.getEventType() == EventType.ACCESS_DENIED) {
            events.classify(event.getId(), EventClassification.NORMAL);
            return event.withClassification(EventClassification.NORMAL);
        }
        var current = presence.lockOrCreate(event.getCredentialRef());
        var decision = PresencePolicy.assess(current, event.getEventType(), event.getOccurredAt());
        if (decision.advance()) {
            presence.advance(event.getCredentialRef(), decision.inside(),
                    event.getOccurredAt(), event.getId());
        }
        events.classify(event.getId(), decision.classification());
        return event.withClassification(decision.classification());
    }

    private Result existingResult(AccessEvent existing, Command command, Instant occurredAt) {
        if (existing.getEventType() != command.eventType()
                || !Objects.equals(existing.getCredentialRef(), command.credentialRef())
                || !existing.getOccurredAt().equals(occurredAt)) {
            throw new ConflictException("External event ID already identifies a different event");
        }
        return new Result(existing, false);
    }

    public record Command(UUID deviceId, String externalEventId, EventType eventType,
                          String credentialRef, Instant occurredAt) {
    }

    public record Result(AccessEvent event, boolean created) {
    }
}
