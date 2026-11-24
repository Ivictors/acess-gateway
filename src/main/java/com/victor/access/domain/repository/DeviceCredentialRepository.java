package com.victor.access.domain.repository;

import java.util.Optional;
import java.util.UUID;

public interface DeviceCredentialRepository {
    void create(UUID deviceId, byte[] tokenHash);

    void replace(UUID deviceId, byte[] tokenHash);

    Optional<UUID> findDeviceIdByTokenHash(byte[] tokenHash);
}
