package com.victor.access.infrastructure.persistence;

import com.victor.access.domain.repository.DeviceCredentialRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDeviceCredentialRepository implements DeviceCredentialRepository {
    private final JdbcClient jdbcClient;

    public JdbcDeviceCredentialRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void create(UUID deviceId, byte[] tokenHash) {
        jdbcClient.sql("INSERT INTO device_credential (device_id, token_hash) VALUES (:deviceId, :tokenHash)")
                .param("deviceId", deviceId)
                .param("tokenHash", tokenHash)
                .update();
    }

    @Override
    public void replace(UUID deviceId, byte[] tokenHash) {
        jdbcClient.sql("""
                        INSERT INTO device_credential (device_id, token_hash, rotated_at)
                        VALUES (:deviceId, :tokenHash, NOW())
                        ON CONFLICT (device_id) DO UPDATE
                        SET token_hash = EXCLUDED.token_hash, rotated_at = NOW()
                        """)
                .param("tokenHash", tokenHash)
                .param("deviceId", deviceId)
                .update();
    }

    @Override
    public Optional<UUID> findDeviceIdByTokenHash(byte[] tokenHash) {
        return jdbcClient.sql("SELECT device_id FROM device_credential WHERE token_hash = :tokenHash")
                .param("tokenHash", tokenHash)
                .query((resultSet, rowNumber) -> resultSet.getObject("device_id", UUID.class))
                .optional();
    }
}
