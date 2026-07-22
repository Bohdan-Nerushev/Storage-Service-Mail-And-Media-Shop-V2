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

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.RemoveBucketArgs;
import io.minio.ListObjectsArgs;
import io.minio.Result;
import io.minio.messages.Item;
import io.minio.messages.ListAllMyBucketsResult;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import java.util.concurrent.TimeUnit;
import java.util.List;

import java.io.InputStream;

@Service
@Validated
@RequiredArgsConstructor
public class MinioService {

    private final @NotNull MinioClient client;
    private final @NotNull MinioProperties properties;

    public @NotBlank String upload(final @NotNull MultipartFile file, 
                                   final @NotBlank String bucketName,
                                   final @NotBlank String objectName
                                ) {
        try {
            final String contentType = file.getContentType();
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
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

    public @NotBlank String getPresignedUrl(final @NotBlank String bucketName, final @NotBlank String objectName) {
        try {
            // Generate temporary presigned download link (expires in 2 hours)
            return client.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucketName)
                            .object(objectName)
                            .expiry(2, TimeUnit.HOURS)
                            .build()
            );
        } catch (final Exception e) {
            throw new FileStorageException("Error generating presigned URL for: " + objectName, e);
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

    public @NotNull List<ListAllMyBucketsResult.Bucket> listBuckets() {
        try {
            // Retrieve list of all buckets from MinIO
            return client.listBuckets();
        } catch (final Exception e) {
            throw new FileStorageException("Error listing buckets", e);
        }
    }

    public boolean isBucketEmpty(final @NotBlank String bucketName) {
        try {
            // List objects to check if bucket has any items
            final Iterable<Result<Item>> results = client.listObjects(
                    ListObjectsArgs.builder().bucket(bucketName).build()
            );
            return !results.iterator().hasNext();
        } catch (final Exception e) {
            throw new FileStorageException("Error checking if bucket is empty: " + bucketName, e);
        }
    }

    public void clearBucket(final @NotBlank String bucketName) {
        try {
            // List and delete all objects in the bucket
            final Iterable<Result<Item>> results = client.listObjects(
                    ListObjectsArgs.builder().bucket(bucketName).build()
            );
            for (final Result<Item> result : results) {
                final Item item = result.get();
                client.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket(bucketName)
                                .object(item.objectName())
                                .build()
                );
            }
        } catch (final Exception e) {
            throw new FileStorageException("Error clearing objects from bucket: " + bucketName, e);
        }
    }

    public void deleteBucket(final @NotBlank String bucketName) {
        try {
            // Delete bucket from MinIO
            client.removeBucket(RemoveBucketArgs.builder().bucket(bucketName).build());
        } catch (final Exception e) {
            throw new FileStorageException("Error deleting bucket: " + bucketName, e);
        }
    }
}
