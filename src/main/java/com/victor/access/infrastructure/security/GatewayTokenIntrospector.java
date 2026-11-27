package com.victor.access.infrastructure.security;

import com.victor.access.application.DeviceTokenService;
import com.victor.access.domain.repository.DeviceCredentialRepository;
import com.victor.access.domain.repository.DeviceRepository;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
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
    private final boolean oidcEnabled;
    private final String oidcAudience;
    private final JwtDecoder oidcDecoder;

    public GatewayTokenIntrospector(DeviceCredentialRepository credentials, DeviceRepository devices,
                                   DeviceTokenService tokens,
                                   @Value("${access.security.admin-token:}") String adminToken,
                                   @Value("${access.security.oidc-enabled:false}") boolean oidcEnabled,
                                   @Value("${access.security.oidc-issuer:}") String oidcIssuer,
                                   @Value("${access.security.oidc-jwk-set-uri:}") String oidcJwkSetUri,
                                   @Value("${access.security.oidc-audience:access-gateway}") String oidcAudience) {
        if (!oidcEnabled && (adminToken.length() < 32 || adminToken.isBlank())) {
            throw new IllegalArgumentException("access.security.admin-token must have at least 32 characters");
        }
        if (oidcEnabled && (oidcIssuer.isBlank() || oidcJwkSetUri.isBlank() || oidcAudience.isBlank())) {
            throw new IllegalArgumentException("OIDC issuer, JWK set URI and audience are required");
        }
        this.credentials = credentials;
        this.devices = devices;
        this.tokens = tokens;
        this.adminTokenHash = tokens.hash(adminToken);
        this.oidcEnabled = oidcEnabled;
        this.oidcAudience = oidcAudience;
        if (oidcEnabled) {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(oidcJwkSetUri).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(oidcIssuer));
            this.oidcDecoder = decoder;
        } else {
            this.oidcDecoder = null;
        }
    }

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        if (oidcEnabled && token.chars().filter(character -> character == '.').count() == 2) {
            return introspectOidc(token);
        }
        byte[] tokenHash = tokens.hash(token);
        if (!oidcEnabled && MessageDigest.isEqual(adminTokenHash, tokenHash)) {
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

    private OAuth2AuthenticatedPrincipal introspectOidc(String token) {
        try {
            Jwt jwt = oidcDecoder.decode(token);
            Object realmAccess = jwt.getClaim("realm_access");
            if (jwt.getAudience() == null || !jwt.getAudience().contains(oidcAudience)
                    || !(realmAccess instanceof Map<?, ?> roles)
                    || !(roles.get("roles") instanceof List<?> roleNames)
                    || !roleNames.contains("access-admin")) {
                throw new BadOpaqueTokenException("Invalid token");
            }
            return new DefaultOAuth2AuthenticatedPrincipal(jwt.getSubject(), jwt.getClaims(),
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        } catch (JwtException exception) {
            throw new BadOpaqueTokenException("Invalid token");
        }
    }
}
