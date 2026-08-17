package com.example.minio.integration.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Base class for integration tests.
 *
 * Starts the following infrastructure once per test suite run (shared between all subclasses
 * via static containers):
 * - PostgreSQL 16 via Testcontainers
 * - MinIO (latest) via Testcontainers GenericContainer
 * - WireMock HTTP server acting as Keycloak JWKS endpoint
 *
 * JWT tokens for tests are created by {@link JwtTokenFactory}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
public abstract class BaseIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    // MockMvc must be built manually — @AutoConfigureMockMvc was removed in Spring Boot 4.x
    protected MockMvc mockMvc;

    public static final String TEST_BUCKET = "test-avatars";
    public static final int MINIO_API_PORT = 9000;

    // Shared across all integration test subclasses to avoid repeated container startup
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("storage_test")
                    .withUsername("test")
                    .withPassword("test");

    public static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse("minio/minio:latest"))
                    .withEnv("MINIO_ROOT_USER", "minioadmin")
                    .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
                    .withCommand("server", "/data")
                    .withExposedPorts(MINIO_API_PORT);

    // WireMock acts as the Keycloak JWKS endpoint; started once statically alongside containers
    public static final WireMockServer WIREMOCK;
    public static final JwtTokenFactory JWT_FACTORY;

    static {
        // Start containers manually so they persist across the entire test suite run (JVM lifetime)
        POSTGRES.start();
        MINIO.start();

        JWT_FACTORY = new JwtTokenFactory();

        WIREMOCK = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        WIREMOCK.start();

        // Expose JWKS endpoint that Spring Security will call to verify JWT signatures
        WIREMOCK.stubFor(WireMock.get(WireMock.urlPathEqualTo("/protocol/openid-connect/certs"))
                .willReturn(WireMock.okJson(JWT_FACTORY.jwksJson())));
    }

    @DynamicPropertySource
    static void overrideProperties(final DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // MinIO
        final String minioUrl = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(MINIO_API_PORT);
        registry.add("minio.url", () -> minioUrl);
        registry.add("minio.access-key", () -> "minioadmin");
        registry.add("minio.secret-key", () -> "minioadmin");
        registry.add("minio.bucket-name", () -> TEST_BUCKET);

        // Keycloak (WireMock)
        final String issuer = "http://localhost:" + WIREMOCK.port() + "/realms/test";
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://localhost:" + WIREMOCK.port() + "/protocol/openid-connect/certs");
        registry.add("app.security.audience", () -> "mail-and-media-shop-app");
    }

    @BeforeEach
    void ensureTestBucketExists() throws Exception {
        // Rebuild MockMvc with the security filter chain before each test
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        final MinioClient minioClient = MinioClient.builder()
                .endpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(MINIO_API_PORT))
                .credentials("minioadmin", "minioadmin")
                .build();

        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(TEST_BUCKET).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(TEST_BUCKET).build());
        }
    }

    /**
     * Creates a signed JWT token for the given subject with the USER role.
     */
    protected String bearerToken(final String subject) {
        return "Bearer " + JWT_FACTORY.createToken(
                subject,
                "http://localhost:" + WIREMOCK.port() + "/realms/test",
                "mail-and-media-shop-app",
                java.util.List.of("USER"));
    }
}
