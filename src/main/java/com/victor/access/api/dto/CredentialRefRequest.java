package com.victor.access.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CredentialRefRequest(@NotBlank @Size(max = 100) String credentialRef) {
}
