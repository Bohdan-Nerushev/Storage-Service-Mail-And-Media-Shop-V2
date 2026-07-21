package com.example.minio.config;

import io.minio.MinioClient;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Bean
    public @NotNull MinioClient minioClient(final @NotNull MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getUrl())
                .credentials(
                        properties.getAccessKey(),
                        properties.getSecretKey()
                ).build();
    }
}
