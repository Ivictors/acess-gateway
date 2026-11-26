package com.victor.access.infrastructure.persistence;

import com.victor.access.domain.repository.CredentialRefRepository;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCredentialRefRepository implements CredentialRefRepository {
    private final JdbcClient jdbc;

    public JdbcCredentialRefRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean create(String ref) {
        return jdbc.sql("INSERT INTO credential_ref (value) VALUES (:ref) ON CONFLICT DO NOTHING")
                .param("ref", ref).update() == 1;
    }

    @Override
    public boolean exists(String ref) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM credential_ref WHERE value = :ref)")
                .param("ref", ref).query(Boolean.class).single();
    }

    @Override
    public void assign(UUID deviceId, String ref) {
        jdbc.sql("""
                INSERT INTO device_credential_ref (device_id, credential_ref)
                VALUES (:deviceId, :ref) ON CONFLICT DO NOTHING
                """).param("deviceId", deviceId).param("ref", ref).update();
    }

    @Override
    public void unassign(UUID deviceId, String ref) {
        jdbc.sql("DELETE FROM device_credential_ref WHERE device_id = :deviceId AND credential_ref = :ref")
                .param("deviceId", deviceId).param("ref", ref).update();
    }

    @Override
    public boolean isAssigned(UUID deviceId, String ref) {
        return jdbc.sql("""
                SELECT credential_ref FROM device_credential_ref
                WHERE device_id = :deviceId AND credential_ref = :ref
                FOR SHARE
                """).param("deviceId", deviceId).param("ref", ref)
                .query(String.class).optional().isPresent();
    }
}
