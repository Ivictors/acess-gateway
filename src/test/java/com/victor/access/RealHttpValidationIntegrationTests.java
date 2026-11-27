package com.victor.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.victor.access.application.CreateDeviceUseCase;
import com.victor.access.domain.repository.CredentialRefRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class RealHttpValidationIntegrationTests {
    @Autowired CreateDeviceUseCase createDevice;
    @Autowired CredentialRefRepository refs;
    @Autowired Environment environment;

    @Test
    void invalidEventRemainsBadRequestThroughServletErrorDispatch() throws Exception {
        var registration = createDevice.execute("SN-real-http-" + UUID.randomUUID(), "Door");
        UUID deviceId = registration.device().getId();
        refs.create("real-http-card");
        refs.assign(deviceId, "real-http-card");
        int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        String body = """
                {"deviceId":"%s","externalEventId":"missing-time","eventType":"ACCESS_GRANTED",
                 "credentialRef":"real-http-card"}
                """.formatted(deviceId);
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/device-events"))
                            .header("Authorization", "Bearer " + registration.apiKey())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, response.statusCode());
            assertFalse(response.body().contains(registration.apiKey()));
            assertFalse(response.body().contains("real-http-card"));

            var directError = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/error"))
                            .header("Authorization", "Bearer " + registration.apiKey())
                            .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, directError.statusCode());
        }
    }
}
