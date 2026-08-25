package com.example.minio.service;

import com.example.minio.config.MinioProperties;
import com.example.minio.exception.FileStorageException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

@Service
@Validated
@RequiredArgsConstructor
public class MinioService {

    private final @NotNull MinioClient client;
    private final @NotNull MinioProperties properties;

    public @NotBlank String upload(final @NotNull MultipartFile file,
                                   final @NotBlank String bucketName,
                                   final @NotBlank String objectName,
                                   final @NotBlank String contentType
    ) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectName)
                    .stream(
                            file.getInputStream(),
                            file.getSize(),
                            -1L
                    )
                    .contentType(contentType)
                    .build()
            );

            return objectName;
        } catch (final Exception e) {
            throw new FileStorageException(e);
        }
    }

    public @NotNull InputStream download(final @NotBlank String bucketName, final @NotBlank String objectName) {
        try {
            // Fetch object stream from MinIO bucket
            return client.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            );
        } catch (final Exception e) {
            throw new FileStorageException("Error downloading file from MinIO: " + objectName, e);
        }
    }

    public void delete(final @NotBlank String bucketName, final @NotBlank String objectName) {
        try {
            // Remove physical object from MinIO bucket
            client.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build()
            );
        } catch (final Exception e) {
            throw new FileStorageException("Error deleting file from MinIO: " + objectName, e);
        }
    }

    public boolean bucketExists(final @NotBlank String bucketName) {
        try {
            // Check if bucket exists in MinIO
            return client.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
        } catch (final Exception e) {
            throw new FileStorageException("Error checking bucket existence: " + bucketName, e);
        }
    }

    public void createBucket(final @NotBlank String bucketName) {
        try {
            // Create a new bucket in MinIO
            client.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
        } catch (final Exception e) {
            throw new FileStorageException("Error creating bucket: " + bucketName, e);
        }
    }
}
