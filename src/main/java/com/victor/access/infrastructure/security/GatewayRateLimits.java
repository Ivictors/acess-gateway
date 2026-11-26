package com.victor.access.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class GatewayRateLimits {
    private final RateLimitStore store;
    private final int windowSeconds;
    private final int originLimit;
    private final int eventLimit;
    private final int queryLimit;
    private final int adminLimit;

    public GatewayRateLimits(RateLimitStore store,
                             @Value("${access.rate-limit.window-seconds:60}") int windowSeconds,
                             @Value("${access.rate-limit.origin:600}") int originLimit,
                             @Value("${access.rate-limit.events:120}") int eventLimit,
                             @Value("${access.rate-limit.queries:60}") int queryLimit,
                             @Value("${access.rate-limit.admin:300}") int adminLimit) {
        if (windowSeconds < 1 || originLimit < 1 || eventLimit < 1 || queryLimit < 1 || adminLimit < 1) {
            throw new IllegalArgumentException("Rate limits and window must be positive");
        }
        this.store = store;
        this.windowSeconds = windowSeconds;
        this.originLimit = originLimit;
        this.eventLimit = eventLimit;
        this.queryLimit = queryLimit;
        this.adminLimit = adminLimit;
    }

    boolean allowOrigin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (request.getRequestURI().startsWith("/actuator/health")) {
            return true;
        }
        // The socket address is used; untrusted proxy headers cannot select another bucket.
        String identity = "o:" + digest(request.getRemoteAddr());
        return allow(identity, "origin", originLimit, response);
    }

    boolean allowIdentity(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return true;
        }
        String route = route(request);
        if (route == null) {
            return true;
        }
        int limit = switch (route) {
            case "events" -> eventLimit;
            case "queries" -> queryLimit;
            default -> adminLimit;
        };
        return allow("p:" + authentication.getName(), route, limit, response);
    }

    private boolean allow(String identity, String route, int limit, HttpServletResponse response) throws IOException {
        if (store.allow(identity, route, limit, windowSeconds)) {
            return true;
        }
        response.setHeader("Retry-After", Integer.toString(store.secondsUntilReset(windowSeconds)));
        response.sendError(HttpStatus.TOO_MANY_REQUESTS.value());
        return false;
    }

    static String route(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("POST".equals(request.getMethod()) && "/device-events".equals(path)) return "events";
        if ("GET".equals(request.getMethod()) && path.matches("/devices/[^/]+/events")) return "queries";
        if (("POST".equals(request.getMethod()) || "GET".equals(request.getMethod())
                || "DELETE".equals(request.getMethod()))
                && (path.equals("/devices") || path.equals("/credential-refs") || path.startsWith("/devices/"))) return "admin";
        return null;
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
