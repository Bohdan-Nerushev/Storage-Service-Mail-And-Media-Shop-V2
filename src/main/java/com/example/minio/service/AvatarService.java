package com.example.minio.service;

import com.example.minio.config.MinioProperties;
import com.example.minio.dto.AvatarContent;
import com.example.minio.dto.AvatarResponse;
import com.example.minio.entity.AvatarMetadata;
import com.example.minio.exception.AvatarConflictException;
import com.example.minio.exception.ResourceNotFoundException;
import com.example.minio.filter.AvatarFileValidator;
import com.example.minio.repository.AvatarMetadataRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class AvatarService {

    private final AvatarMetadataRepository repository;
    private final AvatarFileValidator fileValidator;
    private final MinioService minioService;
    private final MinioProperties minioProperties;

    public AvatarService(
            final AvatarMetadataRepository repository,
            final AvatarFileValidator fileValidator,
            final MinioService minioService,
            final MinioProperties minioProperties) {
        this.repository = repository;
        this.fileValidator = fileValidator;
        this.minioService = minioService;
        this.minioProperties = minioProperties;
    }

    @Transactional(readOnly = true)
    public AvatarResponse getAvatar(final String subject) {
        return repository.findBySubject(subject).map(AvatarResponse::from).orElseGet(AvatarResponse::absent);
    }

    @Transactional(readOnly = true)
    public AvatarContent getContent(final String subject) {
        final AvatarMetadata metadata = requireAvatar(subject);
        return new AvatarContent(
                minioService.download(minioProperties.getBucketName(), metadata.getObjectKey()),
                metadata.getContentType(),
                metadata.getSize(),
                metadata.getVersion());
    }

    @Transactional
    public AvatarResponse replaceAvatar(final String subject, final MultipartFile file) {
        final String contentType = fileValidator.validate(file);
        final String objectKey = objectKey(subject, contentType);
        minioService.upload(file, minioProperties.getBucketName(), objectKey, contentType);

        try {
            final Optional<AvatarMetadata> existing = repository.findBySubjectForUpdate(subject);
            final AvatarMetadata metadata = existing.orElseGet(AvatarMetadata::new);
            final String oldObjectKey = existing.map(AvatarMetadata::getObjectKey).orElse(null);

            metadata.setSubject(subject);
            metadata.setObjectKey(objectKey);
            metadata.setContentType(contentType);
            metadata.setSize(file.getSize());
            final AvatarMetadata saved = repository.saveAndFlush(metadata);

            if (oldObjectKey != null && !oldObjectKey.equals(objectKey)) {
                deleteAfterCommit(oldObjectKey);
            }
            return AvatarResponse.from(saved);
        } catch (final DataIntegrityViolationException exception) {
            deleteObject(objectKey);
            throw new AvatarConflictException();
        } catch (final RuntimeException exception) {
            deleteObject(objectKey);
            throw exception;
        }
    }

    @Transactional
    public void deleteAvatar(final String subject) {
        final Optional<AvatarMetadata> existing = repository.findBySubjectForUpdate(subject);
        if (existing.isEmpty()) {
            return;
        }
        final String objectKey = existing.get().getObjectKey();
        repository.delete(existing.get());
        deleteAfterCommit(objectKey);
    }

    private AvatarMetadata requireAvatar(final String subject) {
        return repository.findBySubject(subject)
                .orElseThrow(() -> new ResourceNotFoundException("Avatar not found for the authenticated user."));
    }

    private String objectKey(final String subject, final String contentType) {
        return "avatars/" + subject + "/" + UUID.randomUUID() + extensionFor(contentType);
    }

    private String extensionFor(final String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw new IllegalArgumentException("Unsupported avatar content type.");
        };
    }

    private void deleteAfterCommit(final String objectKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteObject(objectKey);
            }
        });
    }

    private void deleteObject(final String objectKey) {
        try {
            minioService.delete(minioProperties.getBucketName(), objectKey);
        } catch (final RuntimeException exception) {
            log.error("Unable to remove orphaned avatar object {}", objectKey, exception);
        }
    }
}
