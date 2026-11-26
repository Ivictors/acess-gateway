package com.victor.access.application;

public class CredentialRefNotAssignedException extends RuntimeException {
    public CredentialRefNotAssignedException() {
        super("Credential reference is not assigned to this device");
    }
}
