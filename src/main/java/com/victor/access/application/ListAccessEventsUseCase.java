package com.victor.access.application;

import com.victor.access.domain.model.AccessEvent;
import com.victor.access.domain.repository.AccessEventRepository;
import com.victor.access.domain.repository.DeviceRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ListAccessEventsUseCase {
    private final DeviceRepository devices;
    private final AccessEventRepository events;

    public ListAccessEventsUseCase(DeviceRepository devices, AccessEventRepository events) {
        this.devices = devices;
        this.events = events;
    }

    public List<AccessEvent> execute(UUID deviceId, int limit) {
        if (limit < 1 || limit > 100) {
            throw new InvalidEventLimitException();
        }
        if (devices.findById(deviceId).isEmpty()) {
            throw new DeviceNotFoundException(deviceId);
        }
        return events.findByDeviceId(deviceId, limit);
    }
}
