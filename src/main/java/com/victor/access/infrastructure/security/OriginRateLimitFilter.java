package com.victor.access.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public class OriginRateLimitFilter extends OncePerRequestFilter {
    private final GatewayRateLimits limits;

    public OriginRateLimitFilter(GatewayRateLimits limits) {
        this.limits = limits;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (limits.allowOrigin(request, response)) chain.doFilter(request, response);
    }
}
