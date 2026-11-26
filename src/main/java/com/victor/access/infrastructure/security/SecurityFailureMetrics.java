package com.victor.access.infrastructure.security;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class SecurityFailureMetrics {
    private final MeterRegistry meters;

    public SecurityFailureMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    public void record(HttpServletRequest request, String outcome) {
        String route = GatewayRateLimits.route(request);
        meters.counter("gateway.security.failures", "route", route == null ? "other" : route,
                "outcome", outcome).increment();
    }

    public AuthenticationEntryPoint unauthorized() {
        return (HttpServletRequest request, HttpServletResponse response, AuthenticationException ignored) -> {
            record(request, "unauthorized");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        };
    }

    public AccessDeniedHandler forbidden() {
        return (HttpServletRequest request, HttpServletResponse response, AccessDeniedException ignored) -> {
            record(request, "forbidden");
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        };
    }
}
