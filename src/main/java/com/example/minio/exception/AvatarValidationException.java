package com.example.minio.exception;

import org.springframework.http.HttpStatus;

public class AvatarValidationException extends RuntimeException {

    private final HttpStatus status;

    public AvatarValidationException(final HttpStatus status, final String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
