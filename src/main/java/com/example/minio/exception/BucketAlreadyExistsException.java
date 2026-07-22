package com.example.minio.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class BucketAlreadyExistsException extends RuntimeException {
    
    public BucketAlreadyExistsException(String message) {
        super(message);
    }
}
