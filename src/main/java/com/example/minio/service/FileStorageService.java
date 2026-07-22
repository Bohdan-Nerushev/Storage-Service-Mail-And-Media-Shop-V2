package com.example.minio.service;

import com.example.minio.config.MinioProperties;
import com.example.minio.dto.FileResponse;
import com.example.minio.entity.FileMetadata;
import com.example.minio.repository.FileMetadataRepository;
import com.example.minio.exception.ResourceNotFoundException;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.multipart.MultipartFile;
import lombok.RequiredArgsConstructor;

import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.UUID;

@Service
@Validated
@RequiredArgsConstructor
public class FileStorageService {

    private final @NotNull MinioService minioService;
    private final @NotNull FileMetadataRepository repository;
    private final @NotNull MinioProperties properties;

    public @NotNull FileResponse upload(final @NotNull MultipartFile file) {
        
        // save data into MinIO
        final String originalFilename = file.getOriginalFilename();
        final String key = (originalFilename != null ? originalFilename : "unknown") + "-" + UUID.randomUUID();
        final String objectKey = minioService.upload(file, key);

        // save metadata into Postgres
        final FileMetadata metadata = new FileMetadata();
        metadata.setObjectKey(objectKey);
        metadata.setOriginalName(originalFilename != null ? originalFilename : "unknown");
        metadata.setBucketName(properties.getBucketName());
        metadata.setContentType(file.getContentType());
        metadata.setSize(file.getSize());
        final FileMetadata savedMetadata = repository.save(metadata);

        final Long savedId = savedMetadata.getId();
        if (savedId == null) {
            throw new IllegalStateException("Metadata ID was not generated after save");
        }

        return new FileResponse(savedId, savedMetadata.getOriginalName());
    }

    public @NotNull FileMetadata getMetadata(final @NotNull Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("File metadata not found for ID: " + id));
    }

    public @NotNull InputStream downloadFile(final @NotNull Long id) {
        final FileMetadata metadata = getMetadata(id);
        return minioService.download(metadata.getObjectKey());
    }

    @Transactional
    public void deleteFile(final @NotNull Long id) {
        final FileMetadata metadata = getMetadata(id);
        minioService.delete(metadata.getObjectKey());
        repository.delete(metadata);
    }
}


