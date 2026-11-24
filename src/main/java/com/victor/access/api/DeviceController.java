package com.victor.access.api;

import com.victor.access.api.dto.CreateDeviceRequest;
import com.victor.access.api.dto.DeviceResponse;
import com.victor.access.api.dto.DeviceRegistrationResponse;
import com.victor.access.api.dto.DeviceCredentialResponse;
import com.victor.access.application.CreateDeviceUseCase;
import com.victor.access.application.GetDeviceUseCase;
import com.victor.access.application.RotateDeviceTokenUseCase;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
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
    private final RotateDeviceTokenUseCase rotateToken;

    public DeviceController(CreateDeviceUseCase createDevice, GetDeviceUseCase getDevice,
                            RotateDeviceTokenUseCase rotateToken) {
        this.createDevice = createDevice;
        this.getDevice = getDevice;
        this.rotateToken = rotateToken;
    }

    @PostMapping
    public ResponseEntity<DeviceRegistrationResponse> create(@Valid @RequestBody CreateDeviceRequest request) {
        DeviceRegistrationResponse response = DeviceRegistrationResponse.from(
                createDevice.execute(request.serialNumber(), request.name()));
        return ResponseEntity.created(URI.create("/devices/" + response.device().id()))
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @GetMapping("/{id}")
    public DeviceResponse get(@PathVariable UUID id) {
        return DeviceResponse.from(getDevice.execute(id));
    }

    @PostMapping("/{id}/credentials")
    public ResponseEntity<DeviceCredentialResponse> rotateCredential(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new DeviceCredentialResponse(rotateToken.execute(id)));
    }
}
