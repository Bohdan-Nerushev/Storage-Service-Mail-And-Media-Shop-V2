package com.example.minio.avatar;

import com.example.minio.dto.AvatarProperties;
import com.example.minio.exception.AvatarValidationException;
import com.example.minio.filter.AvatarFileValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AvatarFileValidatorTest {

    private AvatarProperties properties;
    private AvatarFileValidator validator;

    @BeforeEach
    void setUp() {
        properties = new AvatarProperties();
        properties.setMaxFileSizeBytes(5 * 1024 * 1024);
        validator = new AvatarFileValidator(properties);
    }

    // -------------------------------------------------------------------------
    // Happy paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Detects JPEG by magic bytes, ignoring declared MIME type")
    void detectsJpegSignature() {
        final byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x00, 0x00};
        final MockMultipartFile file = new MockMultipartFile("file", "photo.txt", "text/plain", jpeg);

        assertEquals("image/jpeg", validator.validate(file));
    }

    @Test
    @DisplayName("Detects PNG by magic bytes, ignoring declared MIME type")
    void detectsPngFromContentRatherThanDeclaredMimeType() {
        final byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        final MockMultipartFile file = new MockMultipartFile("file", "avatar.txt", "text/plain", png);

        assertEquals("image/png", validator.validate(file));
    }

    @Test
    @DisplayName("Detects WebP by RIFF/WEBP magic bytes, ignoring declared MIME type")
    void detectsWebpSignature() {
        // RIFF....WEBP — 12-byte WebP header
        final byte[] webp = {
                'R', 'I', 'F', 'F',
                0x00, 0x00, 0x00, 0x00,
                'W', 'E', 'B', 'P'
        };
        final MockMultipartFile file = new MockMultipartFile("file", "img.bin", "application/octet-stream", webp);

        assertEquals("image/webp", validator.validate(file));
    }

    // -------------------------------------------------------------------------
    // Negative paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Rejects an empty file with BAD_REQUEST")
    void rejectsEmptyFile() {
        final MockMultipartFile file = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]);

        final AvatarValidationException ex = assertThrows(
                AvatarValidationException.class, () -> validator.validate(file));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    @DisplayName("Rejects a file without a recognised image signature with UNSUPPORTED_MEDIA_TYPE")
    void rejectsFilesWithoutAnImageSignature() {
        final MockMultipartFile file = new MockMultipartFile("file", "avatar.jpg", "image/jpeg",
                "not an image".getBytes());

        final AvatarValidationException ex = assertThrows(
                AvatarValidationException.class, () -> validator.validate(file));

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getStatus());
    }

    @Test
    @DisplayName("Rejects a file whose size exceeds the configured limit with PAYLOAD_TOO_LARGE")
    void rejectsFilesThatExceedTheConfiguredLimit() {
        properties.setMaxFileSizeBytes(2);
        final byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        final MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", png);

        final AvatarValidationException ex = assertThrows(
                AvatarValidationException.class, () -> validator.validate(file));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, ex.getStatus());
    }

    @Test
    @DisplayName("Rejects a file with a truncated PNG signature (< 8 bytes) as UNSUPPORTED_MEDIA_TYPE")
    void rejectsTruncatedPngSignature() {
        // Only 5 bytes of the 8-byte PNG magic — not enough to match
        final byte[] truncated = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D};
        final MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", truncated);

        final AvatarValidationException ex = assertThrows(
                AvatarValidationException.class, () -> validator.validate(file));

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getStatus());
    }
}
