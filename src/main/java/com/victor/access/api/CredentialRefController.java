package com.victor.access.api;

import com.victor.access.api.dto.CredentialRefRequest;
import com.victor.access.application.ConflictException;
import com.victor.access.application.DeviceNotFoundException;
import com.victor.access.domain.repository.DeviceRepository;
import com.victor.access.domain.repository.CredentialRefRepository;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CredentialRefController {
    private final CredentialRefRepository refs;
    private final DeviceRepository devices;

    public CredentialRefController(CredentialRefRepository refs, DeviceRepository devices) {
        this.refs = refs;
        this.devices = devices;
    }

    @PostMapping("/credential-refs")
    public ResponseEntity<Void> create(@Valid @RequestBody CredentialRefRequest request) {
        if (!refs.create(request.credentialRef())) {
            throw new ConflictException("Credential reference already exists");
        }
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/devices/{id}/credential-refs")
    public ResponseEntity<Void> assign(@PathVariable UUID id,
                                       @Valid @RequestBody CredentialRefRequest request) {
        if (devices.findById(id).isEmpty()) {
            throw new DeviceNotFoundException(id);
        }
        if (!refs.exists(request.credentialRef())) {
            return ResponseEntity.notFound().build();
        }
        refs.assign(id, request.credentialRef());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/devices/{id}/credential-refs")
    public ResponseEntity<Void> revoke(@PathVariable UUID id,
                                       @Valid @RequestBody CredentialRefRequest request) {
        if (devices.findById(id).isEmpty()) {
            throw new DeviceNotFoundException(id);
        }
        refs.unassign(id, request.credentialRef());
        return ResponseEntity.noContent().build();
    }
}
