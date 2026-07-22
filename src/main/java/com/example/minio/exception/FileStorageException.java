package com.example.minio.exception;

public class FileStorageException extends RuntimeException {

    public FileStorageException() {
        super();
    }

    public FileStorageException(final String message) {
        super(message);
    }

    public FileStorageException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public FileStorageException(final Throwable cause) {
        super(cause);
    }
}
