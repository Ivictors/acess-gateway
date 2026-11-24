package com.victor.access.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Service;

@Service
public class DeviceTokenService {
    private final SecureRandom random = new SecureRandom();

    public IssuedToken issue() {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String value = "agd_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        return new IssuedToken(value, hash(value));
    }

    public byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record IssuedToken(String value, byte[] hash) {
    }
}
