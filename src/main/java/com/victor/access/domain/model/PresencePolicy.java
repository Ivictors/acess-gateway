package com.victor.access.domain.model;

import java.time.Instant;

public final class PresencePolicy {
    private PresencePolicy() {
    }

    public static Decision assess(AccessPresence presence, EventType eventType, Instant occurredAt) {
        if (eventType == EventType.ACCESS_DENIED) {
            return new Decision(presence.inside(), false, EventClassification.NORMAL);
        }
        if (presence.lastOccurredAt() != null && !occurredAt.isAfter(presence.lastOccurredAt())) {
            return new Decision(presence.inside(), false, EventClassification.OUT_OF_ORDER);
        }
        return switch (eventType) {
            case ACCESS_GRANTED -> new Decision(true, true,
                    presence.inside() ? EventClassification.ENTRY_WHILE_INSIDE : EventClassification.NORMAL);
            case EXIT_REGISTERED -> new Decision(false, true,
                    presence.inside() ? EventClassification.NORMAL : EventClassification.EXIT_WHILE_OUTSIDE);
            case ACCESS_DENIED -> throw new IllegalStateException("Denied events do not change presence");
        };
    }

    public record Decision(boolean inside, boolean advance, EventClassification classification) {
    }
}
