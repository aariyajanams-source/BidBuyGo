package com.bidbuygo.common.error;

import java.util.List;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * A business-rule rejection. Carries the HTTP status and the human-readable reasons returned to the client.
 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final List<String> reasons;

    public ApiException(HttpStatus status, String message, List<String> reasons) {
        super(message);
        this.status = status;
        this.reasons = List.copyOf(reasons);
    }

    public ApiException(HttpStatus status, String message, String reason) {
        this(status, message, List.of(reason));
    }
}
