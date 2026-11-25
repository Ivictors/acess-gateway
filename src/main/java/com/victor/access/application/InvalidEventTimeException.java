package com.victor.access.application;

public class InvalidEventTimeException extends RuntimeException {
    public InvalidEventTimeException() {
        super("occurredAt exceeds the allowed future clock skew");
    }
}
