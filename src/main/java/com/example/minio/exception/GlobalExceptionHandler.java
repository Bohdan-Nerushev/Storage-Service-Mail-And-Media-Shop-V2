package com.example.minio.exception;

import com.example.minio.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.LocalDateTime;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(final ResourceNotFoundException ex, final HttpServletRequest request) {
        log.warn("Resource not found: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(BucketAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleConflict(final BucketAlreadyExistsException ex, final HttpServletRequest request) {
        log.warn("Resource already exists: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(final IllegalArgumentException ex, final HttpServletRequest request) {
        log.warn("Bad request: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(FileStorageException.class)
    public ResponseEntity<ErrorResponse> handleFileStorage(final FileStorageException ex, final HttpServletRequest request) {
        log.error("MinIO storage exception: ", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(AvatarValidationException.class)
    public ResponseEntity<ErrorResponse> handleAvatarValidation(
            final AvatarValidationException ex, final HttpServletRequest request) {
        return buildResponse(ex.getStatus(), ex.getStatus().getReasonPhrase(), ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(
            final MaxUploadSizeExceededException ex, final HttpServletRequest request) {
        return buildResponse(HttpStatus.PAYLOAD_TOO_LARGE, "Payload Too Large",
                "Avatar file exceeds the allowed size.", request.getRequestURI());
    }

    @ExceptionHandler(AvatarConflictException.class)
    public ResponseEntity<ErrorResponse> handleAvatarConflict(
            final AvatarConflictException ex, final HttpServletRequest request) {
        return buildResponse(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(final Exception ex, final HttpServletRequest request) {
        log.error("Unhandled system exception: ", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred", request.getRequestURI());
    }

    private ResponseEntity<ErrorResponse> buildResponse(final HttpStatus status, final String error, final String message, final String path) {
        final ErrorResponse response = new ErrorResponse(
                LocalDateTime.now(),
                status.value(),
                error,
                message,
                path
        );
        return new ResponseEntity<>(response, status);
    }
}
