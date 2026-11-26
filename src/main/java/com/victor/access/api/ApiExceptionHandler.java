package com.victor.access.api;

import com.victor.access.application.ConflictException;
import com.victor.access.application.CredentialRefNotAssignedException;
import com.victor.access.application.DeviceNotFoundException;
import com.victor.access.application.InvalidEventLimitException;
import com.victor.access.application.InvalidEventTimeException;
import com.victor.access.infrastructure.security.SecurityFailureMetrics;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    private final SecurityFailureMetrics failures;

    public ApiExceptionHandler(SecurityFailureMetrics failures) {
        this.failures = failures;
    }
    @ExceptionHandler(DeviceNotFoundException.class)
    public ProblemDetail notFound(DeviceNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail conflict(ConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(CredentialRefNotAssignedException.class)
    public ProblemDetail forbidden(CredentialRefNotAssignedException exception, HttpServletRequest request) {
        failures.record(request, "forbidden");
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.getMessage());
    }

    @ExceptionHandler({InvalidEventLimitException.class, InvalidEventTimeException.class})
    public ProblemDetail invalidArgument(RuntimeException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
}
