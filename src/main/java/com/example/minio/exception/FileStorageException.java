package com.example.minio.exception;

import org.springframework.lang.Nullable;

public class FileStorageException extends RuntimeException {

    public FileStorageException() {
        super();
    }

    public FileStorageException(final @Nullable String message) {
        super(message);
    }

    public FileStorageException(final @Nullable String message, final @Nullable Throwable cause) {
        super(message, cause);
    }

    public FileStorageException(final @Nullable Throwable cause) {
        super(cause);
    }
}
