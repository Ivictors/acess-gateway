package com.victor.access.application;

public class InvalidEventLimitException extends RuntimeException {
    public InvalidEventLimitException() {
        super("limit must be between 1 and 100");
    }
}
