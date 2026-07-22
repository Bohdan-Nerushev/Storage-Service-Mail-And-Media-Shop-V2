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
import java.util.List;
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
        // Fetch metadata from database or throw 404
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("File metadata not found for ID: " + id));
    }

    public @NotNull InputStream downloadFile(final @NotNull Long id) {
        // Retrieve file metadata details
        final FileMetadata metadata = getMetadata(id);
        // Download binary file stream from MinIO
        return minioService.download(metadata.getObjectKey());
    }

    @Transactional
    public void deleteFile(final @NotNull Long id) {
        // Fetch file metadata details
        final FileMetadata metadata = getMetadata(id);
        // Delete physical object from MinIO
        minioService.delete(metadata.getObjectKey());
        // Delete metadata record from database
        repository.delete(metadata);
    }

    public @NotNull List<FileMetadata> listFiles() {
        // Retrieve all metadata records from database
        return repository.findAll();
    }

    public @NotNull FileMetadata getObjectMetadata(final @NotNull Long id){
        // Fetch metadata from database or throw 404
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("File metadata not found for ID: " + id));
    }    

    @Transactional
    public @NotNull FileResponse updateFile(final @NotNull Long id, final @NotNull MultipartFile file) {
        final FileMetadata metadata = getMetadata(id);
        
        // Delete old file from MinIO
        minioService.delete(metadata.getObjectKey());
        
        // Upload new file to MinIO
        final String originalFilename = file.getOriginalFilename();
        final String key = (originalFilename != null ? originalFilename : "unknown") + "-" + UUID.randomUUID();
        final String objectKey = minioService.upload(file, key);
        
        // Update metadata details
        metadata.setObjectKey(objectKey);
        metadata.setOriginalName(originalFilename != null ? originalFilename : "unknown");
        metadata.setContentType(file.getContentType());
        metadata.setSize(file.getSize());
        
        final FileMetadata updatedMetadata = repository.save(metadata);
        return new FileResponse(updatedMetadata.getId(), updatedMetadata.getOriginalName());
    }

    public @NotNull String getPresignedUrl(final @NotNull Long id) {
        // Fetch file metadata details
        final FileMetadata metadata = getMetadata(id);
        // Generate temporary direct link from MinIO
        return minioService.getPresignedUrl(metadata.getObjectKey());
    }
}



