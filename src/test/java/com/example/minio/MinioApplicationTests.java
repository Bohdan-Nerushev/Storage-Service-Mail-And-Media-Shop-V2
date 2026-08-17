package com.example.minio;

import com.example.minio.service.MinioService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Smoke test: verifies that the Spring application context loads successfully
 * with the test configuration (H2 in-memory DB, mocked MinIO).
 */
@SpringBootTest
class MinioApplicationTests {

    // Prevents MinioInitializer from attempting a real MinIO connection on startup
    @MockitoBean
    private MinioService minioService;

    @Test
    void contextLoads() {
    }
}
