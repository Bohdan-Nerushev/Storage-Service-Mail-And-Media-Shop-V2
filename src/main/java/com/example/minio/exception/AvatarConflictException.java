package com.example.minio.exception;

public class AvatarConflictException extends RuntimeException {

    public AvatarConflictException() {
        super("The avatar changed concurrently. Please retry the request.");
    }
}
