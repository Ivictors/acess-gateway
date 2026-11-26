package com.victor.access.domain.repository;

import java.util.UUID;

public interface CredentialRefRepository {
    boolean create(String ref);
    boolean exists(String ref);
    void assign(UUID deviceId, String ref);
    void unassign(UUID deviceId, String ref);
    boolean isAssigned(UUID deviceId, String ref);
}
