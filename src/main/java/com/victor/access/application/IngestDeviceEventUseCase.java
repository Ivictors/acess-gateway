package com.victor.access.application;

import com.victor.access.domain.model.AccessEvent;
import com.victor.access.domain.model.Device;
import com.victor.access.domain.model.EventType;
import com.victor.access.domain.repository.AccessEventRepository;
import com.victor.access.domain.repository.DeviceRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class IngestDeviceEventUseCase {
    private final DeviceRepository devices;
    private final AccessEventRepository events;

    public IngestDeviceEventUseCase(DeviceRepository devices, AccessEventRepository events) {
        this.devices = devices;
        this.events = events;
    }

    public Result execute(Command command) {
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
                .map(event -> new Result(event, true))
                .orElseGet(() -> {
                    AccessEvent existing = events.findByDeviceIdAndExternalEventId(
                                    command.deviceId(), command.externalEventId())
                            .orElseThrow(() -> new IllegalStateException("Conflicting event was not found"));
                    return existingResult(existing, command, occurredAt);
                });
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
