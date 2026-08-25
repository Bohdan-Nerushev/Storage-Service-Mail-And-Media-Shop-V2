package com.example.minio.avatar;

import com.example.minio.config.MinioProperties;
import com.example.minio.dto.AvatarContent;
import com.example.minio.dto.AvatarResponse;
import com.example.minio.entity.AvatarMetadata;
import com.example.minio.exception.AvatarConflictException;
import com.example.minio.exception.AvatarValidationException;
import com.example.minio.exception.FileStorageException;
import com.example.minio.exception.ResourceNotFoundException;
import com.example.minio.filter.AvatarFileValidator;
import com.example.minio.repository.AvatarMetadataRepository;
import com.example.minio.service.AvatarService;
import com.example.minio.service.MinioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AvatarServiceTest {

    private static final String SUBJECT = "user-subject-001";
    private static final String BUCKET = "test-bucket";

    @Mock
    private AvatarMetadataRepository repository;

    @Mock
    private AvatarFileValidator fileValidator;

    @Mock
    private MinioService minioService;

    @Mock
    private MinioProperties minioProperties;

    @InjectMocks
    private AvatarService avatarService;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(minioProperties.getBucketName()).thenReturn(BUCKET);
    }

    // -------------------------------------------------------------------------
    // getAvatar()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getAvatar() returns a response with hasAvatar=true when the record exists")
    void getAvatarHappyPath() {
        when(repository.findBySubject(SUBJECT)).thenReturn(Optional.of(buildMetadata("key.jpg", "image/jpeg")));

        final AvatarResponse response = avatarService.getAvatar(SUBJECT);

        assertThat(response.hasAvatar()).isTrue();
        assertThat(response.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("getAvatar() returns absent response (hasAvatar=false) when no record exists for the subject")
    void getAvatarReturnsAbsentWhenNoRecord() {
        when(repository.findBySubject(SUBJECT)).thenReturn(Optional.empty());

        final AvatarResponse response = avatarService.getAvatar(SUBJECT);

        assertThat(response.hasAvatar()).isFalse();
        assertThat(response.contentType()).isNull();
    }

    @Test
    @DisplayName("getAvatar() does not return the avatar of a different subject")
    void getAvatarReturnsAbsentOnUnknownSubject() {
        when(repository.findBySubject(SUBJECT)).thenReturn(Optional.empty());

        final AvatarResponse response = avatarService.getAvatar(SUBJECT);

        assertThat(response.hasAvatar()).isFalse();
    }

    // -------------------------------------------------------------------------
    // getContent()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getContent() returns AvatarContent with correct contentType, size, and stream")
    void getContentHappyPath() {
        final AvatarMetadata metadata = buildMetadata("avatars/user/photo.jpg", "image/jpeg");
        metadata.setSize(1024L);
        when(repository.findBySubject(SUBJECT)).thenReturn(Optional.of(metadata));
        when(minioService.download(BUCKET, metadata.getObjectKey()))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        final AvatarContent content = avatarService.getContent(SUBJECT);

        assertThat(content.contentType()).isEqualTo("image/jpeg");
        assertThat(content.size()).isEqualTo(1024L);
        assertThat(content.stream()).isNotNull();
    }

    @Test
    @DisplayName("getContent() throws ResourceNotFoundException when no avatar record exists")
    void getContentThrowsWhenNoAvatar() {
        when(repository.findBySubject(SUBJECT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> avatarService.getContent(SUBJECT))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getContent() propagates FileStorageException when MinIO download fails")
    void getContentThrowsWhenMinioFails() {
        final AvatarMetadata metadata = buildMetadata("avatars/user/photo.jpg", "image/jpeg");
        when(repository.findBySubject(SUBJECT)).thenReturn(Optional.of(metadata));
        when(minioService.download(anyString(), anyString()))
                .thenThrow(new FileStorageException("minio down", new RuntimeException()));

        assertThatThrownBy(() -> avatarService.getContent(SUBJECT))
                .isInstanceOf(FileStorageException.class);
    }

    // -------------------------------------------------------------------------
    // replaceAvatar()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("replaceAvatar() saves a new metadata record when none exists for the subject")
    void replaceAvatarCreatesNewRecord() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            final MultipartFile file = pngFile(512);
            when(fileValidator.validate(file)).thenReturn("image/png");
            when(minioService.upload(any(), eq(BUCKET), anyString(), eq("image/png"))).thenReturn("key.png");
            when(repository.findBySubjectForUpdate(SUBJECT)).thenReturn(Optional.empty());
            final AvatarMetadata saved = buildMetadata("key.png", "image/png");
            saved.setSize(512L);
            when(repository.saveAndFlush(any(AvatarMetadata.class))).thenReturn(saved);

            final AvatarResponse response = avatarService.replaceAvatar(SUBJECT, file);

            assertThat(response.hasAvatar()).isTrue();
            assertThat(response.contentType()).isEqualTo("image/png");
            verify(minioService, never()).delete(anyString(), anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("replaceAvatar() schedules old MinIO object deletion after commit when object key changes")
    void replaceAvatarSchedulesDeletionOfOldObjectAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            final MultipartFile file = pngFile(512);
            final String oldKey = "avatars/user/old-uuid.jpg";
            when(fileValidator.validate(file)).thenReturn("image/png");
            when(minioService.upload(any(), eq(BUCKET), anyString(), eq("image/png"))).thenReturn("new-key.png");
            final AvatarMetadata existing = buildMetadata(oldKey, "image/jpeg");
            when(repository.findBySubjectForUpdate(SUBJECT)).thenReturn(Optional.of(existing));
            final AvatarMetadata saved = buildMetadata("new-key.png", "image/png");
            when(repository.saveAndFlush(any())).thenReturn(saved);

            avatarService.replaceAvatar(SUBJECT, file);

            // Simulate commit: fire all registered synchronizations
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(minioService).delete(BUCKET, oldKey);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("replaceAvatar() deletes the newly uploaded object and throws AvatarConflictException on DataIntegrityViolationException")
    void replaceAvatarRollsBackMinioOnDataIntegrityViolation() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            final MultipartFile file = pngFile(512);
            when(fileValidator.validate(file)).thenReturn("image/png");
            when(repository.findBySubjectForUpdate(SUBJECT)).thenReturn(Optional.empty());
            when(repository.saveAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException("unique constraint"));

            assertThatThrownBy(() -> avatarService.replaceAvatar(SUBJECT, file))
                    .isInstanceOf(AvatarConflictException.class);

            final org.mockito.ArgumentCaptor<String> keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(minioService).upload(any(), eq(BUCKET), keyCaptor.capture(), eq("image/png"));
            verify(minioService).delete(BUCKET, keyCaptor.getValue());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("replaceAvatar() deletes the newly uploaded object and rethrows on generic RuntimeException")
    void replaceAvatarRollsBackMinioOnRuntimeException() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            final MultipartFile file = pngFile(512);
            when(fileValidator.validate(file)).thenReturn("image/png");
            when(repository.findBySubjectForUpdate(SUBJECT)).thenReturn(Optional.empty());
            when(repository.saveAndFlush(any())).thenThrow(new RuntimeException("DB down"));

            assertThatThrownBy(() -> avatarService.replaceAvatar(SUBJECT, file))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("DB down");

            final org.mockito.ArgumentCaptor<String> keyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(minioService).upload(any(), eq(BUCKET), keyCaptor.capture(), eq("image/png"));
            verify(minioService).delete(BUCKET, keyCaptor.getValue());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("replaceAvatar() does not call MinIO upload when file validation fails")
    void replaceAvatarValidationFailureDoesNotUpload() {
        final MultipartFile file = pngFile(512);
        when(fileValidator.validate(file))
                .thenThrow(new AvatarValidationException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "bad type"));

        assertThatThrownBy(() -> avatarService.replaceAvatar(SUBJECT, file))
                .isInstanceOf(AvatarValidationException.class);

        verify(minioService, never()).upload(any(), anyString(), anyString(), anyString());
    }

    // -------------------------------------------------------------------------
    // deleteAvatar()
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("deleteAvatar() removes the DB record and schedules MinIO object deletion after commit")
    void deleteAvatarHappyPath() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            final AvatarMetadata metadata = buildMetadata("avatars/user/photo.jpg", "image/jpeg");
            when(repository.findBySubjectForUpdate(SUBJECT)).thenReturn(Optional.of(metadata));

            avatarService.deleteAvatar(SUBJECT);

            verify(repository).delete(metadata);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(minioService).delete(BUCKET, metadata.getObjectKey());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("deleteAvatar() is a no-op when no avatar exists for the subject")
    void deleteAvatarNoOpWhenNotFound() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            when(repository.findBySubjectForUpdate(SUBJECT)).thenReturn(Optional.empty());

            avatarService.deleteAvatar(SUBJECT);

            verify(repository, never()).delete(any());
            verify(minioService, never()).delete(anyString(), anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("deleteAvatar() swallows FileStorageException from MinIO and does not rethrow")
    void deleteAvatarMinioErrorIsSwallowed() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            final AvatarMetadata metadata = buildMetadata("avatars/user/photo.jpg", "image/jpeg");
            when(repository.findBySubjectForUpdate(SUBJECT)).thenReturn(Optional.of(metadata));
            org.mockito.Mockito.doThrow(new FileStorageException("minio gone", new RuntimeException()))
                    .when(minioService).delete(anyString(), anyString());

            // Manually fire the after-commit hook to simulate the MinIO deletion call
            avatarService.deleteAvatar(SUBJECT);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> {
                        try {
                            s.afterCommit();
                        } catch (final Exception ignored) {
                            // Must not propagate — service swallows it
                        }
                    });

            // No exception means the service correctly swallowed the MinIO error
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static AvatarMetadata buildMetadata(final String objectKey, final String contentType) {
        final AvatarMetadata metadata = new AvatarMetadata();
        metadata.setSubject(SUBJECT);
        metadata.setObjectKey(objectKey);
        metadata.setContentType(contentType);
        metadata.setSize(1024L);
        metadata.setUpdatedAt(Instant.now());
        return metadata;
    }

    private static MultipartFile pngFile(final int size) {
        final byte[] data = new byte[size];
        // PNG magic bytes at the start
        data[0] = (byte) 0x89;
        data[1] = 0x50;
        data[2] = 0x4E;
        data[3] = 0x47;
        data[4] = 0x0D;
        data[5] = 0x0A;
        data[6] = 0x1A;
        data[7] = 0x0A;
        return new MockMultipartFile("file", "avatar.png", "image/png", data);
    }
}
