package com.victor.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
class CredentialRefAuthorizationIntegrationTests {
    private static final String ADMIN = "admin-token-for-integration-tests-only-123456789";
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void requiresRegistrationAndAssignmentBeforeFirstEventButPreservesReplay() throws Exception {
        String registration = mvc.perform(post("/devices").header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serialNumber\":\"SN-assignment-test\",\"name\":\"Door\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(registration, "$.device.id"));
        String token = JsonPath.read(registration, "$.apiKey");
        String event = """
                {"deviceId":"%s","externalEventId":"first","eventType":"ACCESS_GRANTED",
                 "credentialRef":"assigned-card","occurredAt":"2026-01-01T10:00:00Z"}
                """.formatted(id);

        mvc.perform(post("/device-events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isForbidden());
        assertEquals(0L, countEvents(id));
        assertEquals(0L, jdbc.sql("SELECT COUNT(*) FROM access_presence WHERE credential_ref = 'assigned-card'")
                .query(Long.class).single());

        String ref = "{\"credentialRef\":\"assigned-card\"}";
        mvc.perform(post("/credential-refs").header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(ref))
                .andExpect(status().isCreated());
        mvc.perform(post("/credential-refs").header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(ref))
                .andExpect(status().isConflict());
        mvc.perform(post("/device-events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isForbidden());
        mvc.perform(post("/devices/{id}/credential-refs", id).header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(ref))
                .andExpect(status().isNoContent());
        mvc.perform(post("/device-events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isCreated());
        mvc.perform(delete("/devices/{id}/credential-refs", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(ref))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/devices/{id}/credential-refs", id)
                        .header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(ref))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/devices/{id}/credential-refs", id)
                        .header("Authorization", "Bearer " + ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(ref))
                .andExpect(status().isNoContent());
        mvc.perform(post("/device-events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isOk());
        mvc.perform(post("/device-events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(event.replace("first", "second")))
                .andExpect(status().isForbidden());
        assertEquals(1L, countEvents(id));
    }

    private long countEvents(UUID id) {
        return jdbc.sql("SELECT COUNT(*) FROM access_event WHERE device_id = :id")
                .param("id", id).query(Long.class).single();
    }
}
