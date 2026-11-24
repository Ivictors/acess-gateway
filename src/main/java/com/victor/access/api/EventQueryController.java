package com.victor.access.api;

import com.victor.access.api.dto.AccessEventResponse;
import com.victor.access.application.ListAccessEventsUseCase;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/devices/{deviceId}/events")
public class EventQueryController {
    private final ListAccessEventsUseCase listEvents;

    public EventQueryController(ListAccessEventsUseCase listEvents) {
        this.listEvents = listEvents;
    }

    @GetMapping
    public List<AccessEventResponse> list(@PathVariable UUID deviceId,
                                          @RequestParam(defaultValue = "50") int limit) {
        return listEvents.execute(deviceId, limit).stream().map(AccessEventResponse::from).toList();
    }
}
