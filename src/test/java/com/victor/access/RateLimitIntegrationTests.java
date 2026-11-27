package com.victor.access;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.victor.access.infrastructure.security.RateLimitStore;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {"access.rate-limit.events=2", "access.rate-limit.origin=100"})
@Transactional
class RateLimitIntegrationTests {
    private static final String ADMIN = "admin-token-for-integration-tests-only-123456789";
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired Clock clock;

    @Test
    void limitsEventRouteByDeviceWithoutBlockingAnotherDevice() throws Exception {
        String first = create("SN-limit-first");
        String second = create("SN-limit-second");
        UUID firstId = UUID.fromString(JsonPath.read(first, "$.device.id"));
        UUID secondId = UUID.fromString(JsonPath.read(second, "$.device.id"));
        String firstToken = JsonPath.read(first, "$.apiKey");
        String secondToken = JsonPath.read(second, "$.apiKey");
        String ref = "{\"credentialRef\":\"card-limit\"}";
        mvc.perform(post("/credential-refs").header("Authorization", "Bearer " + ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(ref)).andExpect(status().isCreated());
        for (UUID id : new UUID[] {firstId, secondId}) {
            mvc.perform(post("/devices/{id}/credential-refs", id)
                    .header("Authorization", "Bearer " + ADMIN)
                    .contentType(MediaType.APPLICATION_JSON).content(ref)).andExpect(status().isNoContent());
        }
        mvc.perform(post("/device-events").header("Authorization", "Bearer " + firstToken)
                .contentType(MediaType.APPLICATION_JSON).content(event(firstId, "one")))
                .andExpect(status().isCreated());
        mvc.perform(post("/device-events").header("Authorization", "Bearer " + firstToken)
                .contentType(MediaType.APPLICATION_JSON).content(event(firstId, "two")))
                .andExpect(status().isCreated());
        mvc.perform(post("/device-events").header("Authorization", "Bearer " + firstToken)
                .contentType(MediaType.APPLICATION_JSON).content(event(firstId, "three")))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        mvc.perform(post("/device-events").header("Authorization", "Bearer " + secondToken)
                .contentType(MediaType.APPLICATION_JSON).content(event(secondId, "one")))
                .andExpect(status().isCreated());
    }

    @Test
    void limitsUnauthenticatedOriginWithoutTrustingForwardedAddress() throws Exception {
        for (int i = 0; i < 100; i++) {
            String forwarded = "198.51.100." + i;
            mvc.perform(post("/devices").with(request -> {
                        request.setRemoteAddr("192.0.2.88");
                        return request;
                    }).header("X-Forwarded-For", forwarded))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/devices").with(request -> {
                    request.setRemoteAddr("192.0.2.88");
                    return request;
                }).header("X-Forwarded-For", "203.0.113.1"))
                .andExpect(status().isTooManyRequests());
        mvc.perform(post("/devices").with(request -> {
                    request.setRemoteAddr("192.0.2.89");
                    return request;
                })).andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void independentStoresShareAtomicDatabaseCounterAndRouteBuckets() throws Exception {
        String identity = "p:" + UUID.randomUUID();
        RateLimitStore firstStore = new RateLimitStore(jdbc, clock, 60);
        RateLimitStore secondStore = new RateLimitStore(jdbc, clock, 60);
        CountDownLatch start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> {
                start.await();
                int allowed = 0;
                for (int i = 0; i < 4; i++) if (firstStore.allow(identity, "events", 5, 60)) allowed++;
                return allowed;
            });
            var second = workers.submit(() -> {
                start.await();
                int allowed = 0;
                for (int i = 0; i < 4; i++) if (secondStore.allow(identity, "events", 5, 60)) allowed++;
                return allowed;
            });
            start.countDown();
            assertEquals(5, first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS));
            assertEquals(true, secondStore.allow(identity, "queries", 5, 60));
        } finally {
            workers.shutdownNow();
            jdbc.sql("DELETE FROM rate_limit_window WHERE identity_key = :id")
                    .param("id", identity).update();
        }
    }

    private String create(String serial) throws Exception {
        return mvc.perform(post("/devices").header("Authorization", "Bearer " + ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"serialNumber\":\"" + serial + "\",\"name\":\"Door\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private String event(UUID id, String external) {
        return """
                {"deviceId":"%s","externalEventId":"%s","eventType":"ACCESS_GRANTED",
                 "credentialRef":"card-limit","occurredAt":"2026-01-01T10:00:00Z"}
                """.formatted(id, external);
    }
}
