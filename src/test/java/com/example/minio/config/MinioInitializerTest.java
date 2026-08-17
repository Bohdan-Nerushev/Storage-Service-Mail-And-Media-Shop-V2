package com.example.minio.config;

import com.example.minio.exception.FileStorageException;
import com.example.minio.service.MinioService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MinioInitializerTest {

    private static final String BUCKET = "user-images";

    @Mock
    private MinioService minioService;

    @Mock
    private MinioProperties properties;

    @InjectMocks
    private MinioInitializer initializer;

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Does not create the bucket when it already exists in MinIO")
    void doesNotCreateBucketWhenAlreadyExists() throws Exception {
        when(properties.getBucketName()).thenReturn(BUCKET);
        when(minioService.bucketExists(BUCKET)).thenReturn(true);

        initializer.run();

        verify(minioService, never()).createBucket(BUCKET);
    }

    // -------------------------------------------------------------------------
    // Negative paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Creates the bucket exactly once when it does not exist in MinIO")
    void createsBucketWhenNotExists() throws Exception {
        when(properties.getBucketName()).thenReturn(BUCKET);
        when(minioService.bucketExists(BUCKET)).thenReturn(false);

        initializer.run();

        verify(minioService).createBucket(BUCKET);
    }

    @Test
    @DisplayName("Swallows FileStorageException and does not rethrow when MinIO is unavailable on startup")
    void swallowsExceptionWhenMinioUnavailable() throws Exception {
        when(properties.getBucketName()).thenReturn(BUCKET);
        when(minioService.bucketExists(BUCKET))
                .thenThrow(new FileStorageException("connection refused", new RuntimeException()));

        // Must complete without throwing — startup failure is logged, not propagated
        initializer.run();

        verify(minioService, never()).createBucket(BUCKET);
    }
}
