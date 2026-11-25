package com.victor.access.infrastructure.security;

import com.victor.access.application.DeviceTokenService;
import com.victor.access.domain.repository.DeviceCredentialRepository;
import com.victor.access.domain.repository.DeviceRepository;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.stereotype.Component;

@Component
public class GatewayTokenIntrospector implements OpaqueTokenIntrospector {
    private final DeviceCredentialRepository credentials;
    private final DeviceRepository devices;
    private final DeviceTokenService tokens;
    private final byte[] adminTokenHash;

    public GatewayTokenIntrospector(DeviceCredentialRepository credentials, DeviceRepository devices,
                                   DeviceTokenService tokens,
                                   @Value("${access.security.admin-token}") String adminToken) {
        if (adminToken.length() < 32 || adminToken.isBlank()) {
            throw new IllegalArgumentException("access.security.admin-token must have at least 32 characters");
        }
        this.credentials = credentials;
        this.devices = devices;
        this.tokens = tokens;
        this.adminTokenHash = tokens.hash(adminToken);
    }

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        byte[] tokenHash = tokens.hash(token);
        if (MessageDigest.isEqual(adminTokenHash, tokenHash)) {
            return new DefaultOAuth2AuthenticatedPrincipal("admin", Map.of("sub", "admin"),
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        }
        UUID deviceId = credentials.findDeviceIdByTokenHash(tokenHash)
                .orElseThrow(() -> new BadOpaqueTokenException("Invalid token"));
        if (devices.findById(deviceId).filter(device -> device.isActive()).isEmpty()) {
            throw new BadOpaqueTokenException("Invalid token");
        }
        return new DefaultOAuth2AuthenticatedPrincipal(deviceId.toString(),
                Map.of("sub", deviceId.toString()),
                List.of(new SimpleGrantedAuthority("ROLE_DEVICE")));
    }
}
