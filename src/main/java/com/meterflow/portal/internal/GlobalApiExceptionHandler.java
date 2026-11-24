package com.meterflow.portal.internal;

import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Standard RFC 9457 ProblemDetail error adapter for REST API controllers (ADR-006, QA5).
 */
@RestControllerAdvice(annotations = RestController.class)
public class GlobalApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationError(MethodArgumentNotValidException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Request validation failed"
        );
        detail.setTitle("Validation Error");
        detail.setType(URI.create("urn:meterflow:error:validation"));
        attachCorrelationId(detail);
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage() != null ? ex.getMessage() : "Invalid request parameter"
        );
        detail.setTitle("Bad Request");
        detail.setType(URI.create("urn:meterflow:error:bad-request"));
        attachCorrelationId(detail);
        return detail;
    }

    private void attachCorrelationId(ProblemDetail detail) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            detail.setProperty("correlationId", correlationId);
        }
    }
}
