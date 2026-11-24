package com.victor.access.infrastructure.persistence;

import com.victor.access.domain.model.Device;
import com.victor.access.domain.repository.DeviceRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDeviceRepository implements DeviceRepository {
    private static final RowMapper<Device> DEVICE_ROW_MAPPER = JdbcDeviceRepository::mapDevice;

    private final JdbcClient jdbcClient;

    public JdbcDeviceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<Device> createIfAbsent(UUID id, String serialNumber, String name) {
        return jdbcClient.sql("""
                        INSERT INTO device (id, serial_number, name)
                        VALUES (:id, :serialNumber, :name)
                        ON CONFLICT (serial_number) DO NOTHING
                        RETURNING id, serial_number, name, active, created_at
                        """)
                .param("id", id)
                .param("serialNumber", serialNumber)
                .param("name", name)
                .query(DEVICE_ROW_MAPPER)
                .optional();
    }

    @Override
    public Optional<Device> findById(UUID id) {
        return jdbcClient.sql("""
                        SELECT id, serial_number, name, active, created_at
                        FROM device
                        WHERE id = :id
                        """)
                .param("id", id)
                .query(DEVICE_ROW_MAPPER)
                .optional();
    }

    private static Device mapDevice(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Device(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("serial_number"),
                resultSet.getString("name"),
                resultSet.getBoolean("active"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant()
        );
    }
}
