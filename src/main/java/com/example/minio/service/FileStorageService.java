package com.example.minio.service;

import com.example.minio.config.MinioProperties;
import com.example.minio.dto.FileResponse;
import com.example.minio.entity.FileMetadata;
import com.example.minio.exception.ResourceNotFoundException;
import com.example.minio.repository.FileMetadataRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.multipart.MultipartFile;

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

    public @NotNull FileResponse upload(final @NotNull MultipartFile file, final @NotBlank String bucketName, final String path) {
        if (!minioService.bucketExists(bucketName)) {
            throw new ResourceNotFoundException("Bucket not found: " + bucketName);
        }

        final String originalFilename = file.getOriginalFilename();
        final String resolvedPath;
        if (path != null && !path.isBlank()) {
            resolvedPath = path;
        } else {
            resolvedPath = (originalFilename != null ? originalFilename : "unknown") + "-" + UUID.randomUUID();
        }

        // save data into MinIO
        final String objectKey = minioService.upload(file, bucketName, resolvedPath);

        // save metadata into Postgres (upsert if key exists in bucket)
        final FileMetadata metadata = repository.findByBucketNameAndObjectKey(bucketName, objectKey)
                .orElse(new FileMetadata());

        metadata.setObjectKey(objectKey);
        metadata.setOriginalName(originalFilename != null ? originalFilename : "unknown");
        metadata.setBucketName(bucketName);
        metadata.setContentType(file.getContentType());
        metadata.setSize(file.getSize());
        final FileMetadata savedMetadata = repository.save(metadata);

        final Long savedId = savedMetadata.getId();
        if (savedId == null) {
            throw new IllegalStateException("Metadata ID was not generated after save");
        }

        return new FileResponse(savedId, savedMetadata.getOriginalName());
    }


    public @NotNull FileMetadata getMetadata(final @NotBlank String bucketName, final @NotBlank String path) {
        // Fetch metadata from database using bucket name and object key
        return repository.findByBucketNameAndObjectKey(bucketName, path)
                .orElseThrow(() -> new ResourceNotFoundException("File metadata not found for bucket: " + bucketName + " and path: " + path));
    }


    public @NotNull InputStream downloadFile(final @NotBlank String bucketName, final @NotBlank String path) {
        // Retrieve file metadata details
        final FileMetadata metadata = getMetadata(bucketName, path);
        // Download binary file stream from MinIO
        return minioService.download(bucketName, metadata.getObjectKey());
    }

    @Transactional
    public void deleteFile(final @NotBlank String bucketName, final @NotBlank String path) {
        // Fetch file metadata details
        final FileMetadata metadata = getMetadata(bucketName, path);
        // Delete physical object from MinIO
        minioService.delete(bucketName, metadata.getObjectKey());
        // Delete metadata record from database
        repository.delete(metadata);
    }


    public @NotNull List<FileMetadata> listFiles(final @NotBlank String bucketName) {
        if (!minioService.bucketExists(bucketName)) {
            throw new ResourceNotFoundException("Bucket not found: " + bucketName);
        }
        // Retrieve metadata records for specific bucket
        return repository.findAllByBucketName(bucketName);
    }

    public @NotNull FileMetadata getObjectMetadata(final @NotNull Long id) {
        // Fetch metadata from database or throw 404
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("File metadata not found for ID: " + id));
    }


    @Transactional
    public @NotNull FileResponse updateFile(final @NotBlank String bucketName, final @NotBlank String path, final @NotNull MultipartFile file) {
        final FileMetadata metadata = getMetadata(bucketName, path);

        // Delete old file from MinIO
        minioService.delete(bucketName, metadata.getObjectKey());

        // Upload new file to MinIO (keeping the same path or generating new based on file)
        final String originalFilename = file.getOriginalFilename();
        final String objectKey = minioService.upload(file, bucketName, path);

        // Update metadata details
        metadata.setOriginalName(originalFilename != null ? originalFilename : "unknown");
        metadata.setContentType(file.getContentType());
        metadata.setSize(file.getSize());

        final FileMetadata updatedMetadata = repository.save(metadata);
        return new FileResponse(updatedMetadata.getId(), updatedMetadata.getOriginalName());
    }


    public @NotNull String getPresignedUrl(final @NotBlank String bucketName, final @NotBlank String path) {
        // Fetch file metadata details
        final FileMetadata metadata = getMetadata(bucketName, path);
        // Generate temporary direct link from MinIO
        return minioService.getPresignedUrl(bucketName, metadata.getObjectKey());
    }
}



