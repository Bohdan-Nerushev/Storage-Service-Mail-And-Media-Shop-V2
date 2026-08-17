package com.example.minio.avatar;

import com.example.minio.dto.AvatarProperties;
import com.example.minio.exception.AvatarValidationException;
import com.example.minio.filter.AvatarFileValidator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AvatarFileValidatorTest {

    private final AvatarProperties properties = properties();
    private final AvatarFileValidator validator = new AvatarFileValidator(properties);

    @Test
    void detectsPngFromContentRatherThanDeclaredMimeType() {
        final byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        final MockMultipartFile file = new MockMultipartFile("file", "avatar.txt", "text/plain", png);

        assertEquals("image/png", validator.validate(file));
    }

    @Test
    void rejectsFilesWithoutAnImageSignature() {
        final MockMultipartFile file = new MockMultipartFile("file", "avatar.jpg", "image/jpeg", "not an image".getBytes());

        final AvatarValidationException exception =
                assertThrows(AvatarValidationException.class, () -> validator.validate(file));

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, exception.getStatus());
    }

    @Test
    void rejectsFilesThatExceedTheConfiguredLimit() {
        properties.setMaxFileSizeBytes(2);
        final MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        });

        final AvatarValidationException exception =
                assertThrows(AvatarValidationException.class, () -> validator.validate(file));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exception.getStatus());
    }

    private static AvatarProperties properties() {
        final AvatarProperties properties = new AvatarProperties();
        properties.setMaxFileSizeBytes(5 * 1024 * 1024);
        return properties;
    }
}
