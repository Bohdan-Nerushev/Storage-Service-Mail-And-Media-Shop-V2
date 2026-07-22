package com.example.minio.service;

import com.example.minio.dto.BucketDto;
import com.example.minio.exception.BucketAlreadyExistsException;
import com.example.minio.exception.ResourceNotFoundException;
import io.minio.messages.ListAllMyBucketsResult;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Validated
@RequiredArgsConstructor
public class BucketService {

    private final @NotNull MinioService minioService;

    public @NotNull BucketDto createBucket(final @NotBlank String bucketName) {
        // Check if bucket name is already taken
        if (minioService.bucketExists(bucketName)) {
            throw new BucketAlreadyExistsException("Bucket already exists with name: " + bucketName);
        }
        
        // Create bucket in MinIO
        minioService.createBucket(bucketName);
        
        // Return details with current time as estimate or retrieve from MinIO
        return new BucketDto(bucketName, ZonedDateTime.now());
    }

    public @NotNull List<BucketDto> listBuckets() {
        // Retrieve buckets list from MinIO and map to DTOs
        final List<ListAllMyBucketsResult.Bucket> buckets = minioService.listBuckets();
        return buckets.stream()
                .map(b -> new BucketDto(b.name(), b.creationDate()))
                .collect(Collectors.toList());
    }

    public void deleteBucket(final @NotBlank String bucketName, final boolean force) {
        // Verify bucket exists
        if (!minioService.bucketExists(bucketName)) {
            throw new ResourceNotFoundException("Bucket not found: " + bucketName);
        }
        
        // Check if empty and verify force policy
        final boolean isEmpty = minioService.isBucketEmpty(bucketName);
        if (!isEmpty && !force) {
            throw new IllegalArgumentException("Bucket is not empty. Use force=true to delete with all content.");
        }
        
        // Clear objects if force is true
        if (!isEmpty) {
            minioService.clearBucket(bucketName);
        }
        
        // Delete the bucket
        minioService.deleteBucket(bucketName);
    }
}
