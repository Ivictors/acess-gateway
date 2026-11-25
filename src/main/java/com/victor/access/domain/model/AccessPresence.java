package com.victor.access.domain.model;

import java.time.Instant;

public record AccessPresence(String credentialRef, boolean inside, Instant lastOccurredAt) {
}
