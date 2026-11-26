package com.victor.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import com.victor.access.application.DeviceTokenService;
import com.victor.access.infrastructure.security.GatewayTokenIntrospector;
import com.victor.access.infrastructure.persistence.JdbcCredentialRefRepository;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@AutoConfigureMockMvc
@Transactional
class SecurityIntegrationTests {
    private static final String ADMIN_TOKEN = "admin-token-for-integration-tests-only-123456789";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private JdbcCredentialRefRepository refs;

    @Autowired
    private MeterRegistry meters;

    @Test
    void countsAuthenticationAndAuthorizationFailuresWithBoundedTags() throws Exception {
        double before401 = meters.counter("gateway.security.failures", "route", "events",
                "outcome", "unauthorized").count();
        double before403 = meters.counter("gateway.security.failures", "route", "events",
                "outcome", "forbidden").count();
        mvc.perform(post("/device-events").header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/device-events").header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isForbidden());
        assertEquals(before401 + 1, meters.counter("gateway.security.failures", "route", "events",
                "outcome", "unauthorized").count());
        assertEquals(before403 + 1, meters.counter("gateway.security.failures", "route", "events",
                "outcome", "forbidden").count());
    }

    @Test
    void rejectsMissingInvalidAndWrongRoleTokens() throws Exception {
        String registration = register("SN-security-1");
        String deviceToken = JsonPath.read(registration, "$.apiKey");
        UUID deviceId = UUID.fromString(JsonPath.read(registration, "$.device.id"));

        mvc.perform(post("/devices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNumber\":\"SN-unsecured\",\"name\":\"Entrance\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/devices/{id}", deviceId)
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/devices/{id}", deviceId)
                        .header("Authorization", "Bearer " + deviceToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/devices").header("Authorization", "Bearer " + deviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNumber\":\"SN-forbidden\",\"name\":\"Entrance\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/device-events").header("Authorization", "Bearer " + ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(eventJson(deviceId, "event-1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void deviceTokenCanIngestOnlyForItsOwnDevice() throws Exception {
        String firstRegistration = register("SN-security-2");
        String secondRegistration = register("SN-security-3");
        String firstToken = JsonPath.read(firstRegistration, "$.apiKey");
        UUID firstId = UUID.fromString(JsonPath.read(firstRegistration, "$.device.id"));
        UUID secondId = UUID.fromString(JsonPath.read(secondRegistration, "$.device.id"));
        refs.create("card-1");
        refs.assign(firstId, "card-1");

        mvc.perform(post("/device-events").header("Authorization", "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON).content(eventJson(firstId, "event-own")))
                .andExpect(status().isCreated());
        mvc.perform(post("/device-events").header("Authorization", "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON).content(eventJson(secondId, "event-forged")))
                .andExpect(status().isForbidden());
        assertEquals(0, jdbcClient.sql("SELECT COUNT(*) FROM access_event WHERE device_id = :id")
                .param("id", secondId).query(Integer.class).single());
    }

    @Test
    void rotationRevokesOldTokenAndDoesNotExposeItOnReads() throws Exception {
        String registration = register("SN-security-4");
        String oldToken = JsonPath.read(registration, "$.apiKey");
        UUID deviceId = UUID.fromString(JsonPath.read(registration, "$.device.id"));
        byte[] storedHash = jdbcClient.sql("SELECT token_hash FROM device_credential WHERE device_id = :id")
                .param("id", deviceId).query(byte[].class).single();
        assertEquals(32, storedHash.length);
        assertFalse(Arrays.equals(storedHash, oldToken.getBytes(StandardCharsets.UTF_8)));

        String readBody = mvc.perform(get("/devices/{id}", deviceId)
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(readBody.contains(oldToken));

        String rotatedBody = mvc.perform(post("/devices/{id}/credentials", deviceId)
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.apiKey").exists())
                .andReturn().getResponse().getContentAsString();
        String newToken = JsonPath.read(rotatedBody, "$.apiKey");
        refs.create("card-1");
        refs.assign(deviceId, "card-1");
        assertNotEquals(oldToken, newToken);

        mvc.perform(post("/device-events").header("Authorization", "Bearer " + oldToken)
                        .contentType(MediaType.APPLICATION_JSON).content(eventJson(deviceId, "event-old")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/device-events").header("Authorization", "Bearer " + newToken)
                        .contentType(MediaType.APPLICATION_JSON).content(eventJson(deviceId, "event-new")))
                .andExpect(status().isCreated());
    }

    @Test
    void permitsHealthWithoutAuthenticationAndKeepsApiStateless() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        var result = mvc.perform(get("/devices/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isNotFound())
                .andReturn();
        assertNull(result.getRequest().getSession(false));
    }

    @Test
    void provisionsCredentialForDeviceCreatedBeforeMigration() throws Exception {
        UUID deviceId = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO device (id, serial_number, name) VALUES (:id, :serial, :name)")
                .param("id", deviceId)
                .param("serial", "SN-legacy")
                .param("name", "Legacy entrance")
                .update();

        String body = mvc.perform(post("/devices/{id}/credentials", deviceId)
                        .header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").exists())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.apiKey");
        refs.create("card-1");
        refs.assign(deviceId, "card-1");
        mvc.perform(post("/device-events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(eventJson(deviceId, "event-legacy")))
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsWeakAdministrativeTokenConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new GatewayTokenIntrospector(null, null, new DeviceTokenService(), "short-token"));
    }

    @Test
    void inactiveDeviceTokenCannotAuthenticateEvenForAnExistingEvent() throws Exception {
        String registration = register("SN-security-inactive");
        String token = JsonPath.read(registration, "$.apiKey");
        UUID deviceId = UUID.fromString(JsonPath.read(registration, "$.device.id"));
        String originalEvent = eventJson(deviceId, "event-before-deactivation");
        refs.create("card-1");
        refs.assign(deviceId, "card-1");

        mvc.perform(post("/device-events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(originalEvent))
                .andExpect(status().isCreated());
        jdbcClient.sql("UPDATE device SET active = FALSE WHERE id = :id")
                .param("id", deviceId).update();

        mvc.perform(post("/device-events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(originalEvent))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/device-events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(eventJson(deviceId, "new-event")))
                .andExpect(status().isUnauthorized());
        assertEquals(1, jdbcClient.sql("SELECT COUNT(*) FROM access_event WHERE device_id = :id")
                .param("id", deviceId).query(Integer.class).single());

        jdbcClient.sql("UPDATE device SET active = TRUE WHERE id = :id")
                .param("id", deviceId).update();
        mvc.perform(post("/device-events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(originalEvent))
                .andExpect(status().isOk());
    }

    private String register(String serialNumber) throws Exception {
        return mvc.perform(post("/devices").header("Authorization", "Bearer " + ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNumber\":\"" + serialNumber + "\",\"name\":\"Entrance\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiKey").exists())
                .andReturn().getResponse().getContentAsString();
    }

    private String eventJson(UUID deviceId, String externalEventId) {
        return """
                {"deviceId":"%s","externalEventId":"%s","eventType":"ACCESS_GRANTED",
                 "credentialRef":"card-1","occurredAt":"2026-01-01T10:00:00Z"}
                """.formatted(deviceId, externalEventId);
    }
}
