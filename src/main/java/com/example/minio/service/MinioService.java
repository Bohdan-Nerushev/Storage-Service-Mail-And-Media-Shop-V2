package com.example.minio.service;

import com.example.minio.config.MinioProperties;
import com.example.minio.exception.FileStorageException;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.multipart.MultipartFile;
import lombok.RequiredArgsConstructor;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import java.util.concurrent.TimeUnit;

import java.io.InputStream;

@Service
@Validated
@RequiredArgsConstructor
public class MinioService {

    private final @NotNull MinioClient client;
    private final @NotNull MinioProperties properties;

    public @NotBlank String upload(final @NotNull MultipartFile file, 
                                   final @NotBlank String objectName
                                ) {
        try {
            final String contentType = file.getContentType();
            client.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucketName())
                    .object(objectName)
                    .stream(
                            file.getInputStream(),
                            file.getSize(),
                            -1L
                    )
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .build()
            );

            return objectName;
        } catch (final Exception e) {
            throw new FileStorageException(e);
        }
    }

    public @NotNull InputStream download(final @NotBlank String objectName) {
        try {
            // Fetch object stream from MinIO bucket
            return client.getObject(
                    GetObjectArgs.builder()
                            .bucket(properties.getBucketName())
                            .object(objectName)
                            .build()
            );
        } catch (final Exception e) {
            throw new FileStorageException("Error downloading file from MinIO: " + objectName, e);
        }
    }

    public void delete(final @NotBlank String objectName) {
        try {
            // Remove physical object from MinIO bucket
            client.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(properties.getBucketName())
                            .object(objectName)
                            .build()
            );
        } catch (final Exception e) {
            throw new FileStorageException("Error deleting file from MinIO: " + objectName, e);
        }
    }

    public @NotBlank String getPresignedUrl(final @NotBlank String objectName) {
        try {
            // Generate temporary presigned download link (expires in 2 hours)
            return client.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(properties.getBucketName())
                            .object(objectName)
                            .expiry(2, TimeUnit.HOURS)
                            .build()
            );
        } catch (final Exception e) {
            throw new FileStorageException("Error generating presigned URL for: " + objectName, e);
        }
    }
}
