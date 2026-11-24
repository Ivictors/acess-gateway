package com.victor.access.api;

import com.victor.access.api.dto.AccessEventResponse;
import com.victor.access.api.dto.DeviceEventRequest;
import com.victor.access.application.IngestDeviceEventUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/device-events")
public class DeviceEventController {
    private final IngestDeviceEventUseCase ingestEvent;

    public DeviceEventController(IngestDeviceEventUseCase ingestEvent) {
        this.ingestEvent = ingestEvent;
    }

    @PostMapping
    public ResponseEntity<AccessEventResponse> create(@Valid @RequestBody DeviceEventRequest request) {
        IngestDeviceEventUseCase.Result result = ingestEvent.execute(new IngestDeviceEventUseCase.Command(
                request.deviceId(), request.externalEventId(), request.eventType(),
                request.credentialRef(), request.occurredAt()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(AccessEventResponse.from(result.event()));
    }
}
