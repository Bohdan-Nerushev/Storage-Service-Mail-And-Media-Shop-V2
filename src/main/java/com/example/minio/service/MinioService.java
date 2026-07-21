package com.example.minio.service;

import com.example.minio.config.MinioProperties;
import com.example.minio.exception.FileStorageException;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.multipart.MultipartFile;
import lombok.RequiredArgsConstructor;

@Service
@Validated
@RequiredArgsConstructor
public class MinioService {

    private final @NotNull MinioClient client;
    private final @NotNull MinioProperties properties;

    public @NotBlank String upload(final @NotNull MultipartFile file, 
                                   final @NotBlank String objectName
                                ) {
        try {
            final String contentType = file.getContentType();
            client.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucketName())
                    .object(objectName)
                    .stream(
                            file.getInputStream(),
                            file.getSize(),
                            -1L
                    )
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .build()
            );

            return objectName;
        } catch (final Exception e) {
            throw new FileStorageException(e);
        }
    }
}
