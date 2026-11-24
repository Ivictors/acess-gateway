package com.victor.access.api;

import com.victor.access.api.dto.CreateDeviceRequest;
import com.victor.access.api.dto.DeviceResponse;
import com.victor.access.application.CreateDeviceUseCase;
import com.victor.access.application.GetDeviceUseCase;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/devices")
public class DeviceController {
    private final CreateDeviceUseCase createDevice;
    private final GetDeviceUseCase getDevice;

    public DeviceController(CreateDeviceUseCase createDevice, GetDeviceUseCase getDevice) {
        this.createDevice = createDevice;
        this.getDevice = getDevice;
    }

    @PostMapping
    public ResponseEntity<DeviceResponse> create(@Valid @RequestBody CreateDeviceRequest request) {
        DeviceResponse response = DeviceResponse.from(createDevice.execute(request.serialNumber(), request.name()));
        return ResponseEntity.created(URI.create("/devices/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public DeviceResponse get(@PathVariable UUID id) {
        return DeviceResponse.from(getDevice.execute(id));
    }
}
