package com.example.minio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.lang.NonNull;

@SpringBootApplication
public class MinioApplication {

    public static void main(@NonNull final String[] args) {
        SpringApplication.run(MinioApplication.class, args);
    }
}
