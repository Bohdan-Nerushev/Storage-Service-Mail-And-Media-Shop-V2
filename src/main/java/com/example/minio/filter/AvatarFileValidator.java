package com.example.minio.filter;

import com.example.minio.dto.AvatarProperties;
import com.example.minio.exception.AvatarValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

@Component
public class AvatarFileValidator {

    private static final int SIGNATURE_LENGTH = 12;

    private final AvatarProperties properties;

    public AvatarFileValidator(final AvatarProperties properties) {
        this.properties = properties;
    }

    public String validate(final MultipartFile file) {
        if (file.isEmpty()) {
            throw new AvatarValidationException(HttpStatus.BAD_REQUEST, "Avatar file must not be empty.");
        }
        if (file.getSize() > properties.getMaxFileSizeBytes()) {
            throw new AvatarValidationException(HttpStatus.PAYLOAD_TOO_LARGE, "Avatar file exceeds the allowed size.");
        }

        try (InputStream input = file.getInputStream()) {
            final byte[] signature = input.readNBytes(SIGNATURE_LENGTH);
            if (isJpeg(signature)) {
                return "image/jpeg";
            }
            if (isPng(signature)) {
                return "image/png";
            }
            if (isWebp(signature)) {
                return "image/webp";
            }
        } catch (final IOException exception) {
            throw new AvatarValidationException(HttpStatus.BAD_REQUEST, "Avatar file cannot be read.");
        }

        throw new AvatarValidationException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Only JPEG, PNG, and WebP avatar images are allowed.");
    }

    private boolean isJpeg(final byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;
    }

    private boolean isPng(final byte[] bytes) {
        final byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        if (bytes.length < png.length) {
            return false;
        }
        for (int index = 0; index < png.length; index++) {
            if (bytes[index] != png[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean isWebp(final byte[] bytes) {
        return bytes.length >= SIGNATURE_LENGTH
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }
}
