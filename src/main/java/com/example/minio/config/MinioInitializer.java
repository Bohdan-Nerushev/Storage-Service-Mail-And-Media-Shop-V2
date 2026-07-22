package com.example.minio.config;

import com.example.minio.service.MinioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MinioInitializer implements CommandLineRunner {

    private final MinioService minioService;
    private final MinioProperties properties;

    @Override
    public void run(String... args) {
        final String defaultBucket = properties.getBucketName();
        log.info("Checking default MinIO bucket on startup: {}", defaultBucket);
        try {
            if (!minioService.bucketExists(defaultBucket)) {
                log.info("Default bucket '{}' does not exist. Creating it...", defaultBucket);
                minioService.createBucket(defaultBucket);
                log.info("Default bucket '{}' successfully created.", defaultBucket);
            } else {
                log.info("Default bucket '{}' already exists.", defaultBucket);
            }
        } catch (final Exception e) {
            log.error("Failed to initialize default MinIO bucket on startup: ", e);
        }
    }
}
