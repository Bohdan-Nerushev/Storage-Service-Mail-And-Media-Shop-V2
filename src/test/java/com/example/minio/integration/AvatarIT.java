package com.example.minio.integration;

import com.example.minio.integration.support.BaseIntegrationTest;
import com.example.minio.repository.AvatarMetadataRepository;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.hasItem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration tests for avatar CRUD operations using real PostgreSQL and MinIO instances.
 *
 * Each test method uses a unique subject to ensure isolation.
 * Naming convention: *IT.java → picked up by maven-failsafe-plugin only.
 */
class AvatarIT extends BaseIntegrationTest {

    @Autowired
    private AvatarMetadataRepository repository;

    private String subject;
    private String authHeader;

    @BeforeEach
    void setUpSubject() {
        // Unique subject per test to avoid state leakage between test methods
        subject = "it-user-" + java.util.UUID.randomUUID();
        authHeader = bearerToken(subject);
    }

    // -------------------------------------------------------------------------
    // Happy paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Full happy path: upload avatar → GET metadata → GET content → correct headers")
    void uploadAndDownloadAvatarHappyPath() throws Exception {
        final MockMultipartFile file = pngFile(1024);

        // Upload
        mockMvc.perform(multipart("/api/v1/avatars/me")
                        .file(file)
                        .with(request -> { request.setMethod("PUT"); return request; })
                        .header(HttpHeaders.AUTHORIZATION, authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAvatar").value(true))
                .andExpect(jsonPath("$.contentType").value("image/png"));

        // GET metadata
        mockMvc.perform(get("/api/v1/avatars/me")
                        .header(HttpHeaders.AUTHORIZATION, authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAvatar").value(true));

        // GET binary content
        mockMvc.perform(get("/api/v1/avatars/me/content")
                        .header(HttpHeaders.AUTHORIZATION, authHeader))
                .andExpect(status().isOk())
                .andExpect(header().exists("Cache-Control"))
                .andExpect(header().exists("ETag"))
                .andExpect(header().stringValues("Vary", hasItem("Authorization")));
    }

    @Test
    @DisplayName("Second upload replaces the avatar and deletes the old MinIO object")
    void replaceAvatarDeletesOldObjectFromMinio() throws Exception {
        // First upload — record the initial objectKey
        mockMvc.perform(multipart("/api/v1/avatars/me")
                        .file(pngFile(512))
                        .with(request -> { request.setMethod("PUT"); return request; })
                        .header(HttpHeaders.AUTHORIZATION, authHeader))
                .andExpect(status().isOk());

        final String firstObjectKey = repository.findBySubject(subject)
                .orElseThrow().getObjectKey();

        // Give the post-commit MinIO cleanup a moment to execute
        Thread.sleep(200);

        // Second upload
        mockMvc.perform(multipart("/api/v1/avatars/me")
                        .file(jpegFile(512))
                        .with(request -> { request.setMethod("PUT"); return request; })
                        .header(HttpHeaders.AUTHORIZATION, authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentType").value("image/jpeg"));

        Thread.sleep(200);

        // The old object must no longer exist in MinIO
        final List<String> objectKeys = listMinioObjects();
        assertThat(objectKeys).doesNotContain(firstObjectKey);
    }

    @Test
    @DisplayName("DELETE removes the DB record and the MinIO object")
    void deleteAvatarRemovesRecordAndObject() throws Exception {
        // Upload first
        mockMvc.perform(multipart("/api/v1/avatars/me")
                        .file(pngFile(512))
                        .with(request -> { request.setMethod("PUT"); return request; })
                        .header(HttpHeaders.AUTHORIZATION, authHeader))
                .andExpect(status().isOk());

        final String objectKey = repository.findBySubject(subject).orElseThrow().getObjectKey();

        // Delete
        mockMvc.perform(delete("/api/v1/avatars/me")
                        .header(HttpHeaders.AUTHORIZATION, authHeader))
                .andExpect(status().isNoContent());

        Thread.sleep(200);

        // DB record must be gone
        assertThat(repository.findBySubject(subject)).isEmpty();

        // MinIO object must be removed
        assertThat(listMinioObjects()).doesNotContain(objectKey);
    }

    // -------------------------------------------------------------------------
    // Negative paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /content returns 404 when no avatar has ever been uploaded")
    void getAvatarContentReturns404WhenNeverUploaded() throws Exception {
        mockMvc.perform(get("/api/v1/avatars/me/content")
                        .header(HttpHeaders.AUTHORIZATION, authHeader))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("PUT /me returns 415 when file content is not a supported image type")
    void uploadRejectsInvalidFileType() throws Exception {
        final MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", "not an image".getBytes());

        mockMvc.perform(multipart("/api/v1/avatars/me")
                        .file(file)
                        .with(request -> { request.setMethod("PUT"); return request; })
                        .header(HttpHeaders.AUTHORIZATION, authHeader))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    @DisplayName("PUT /me returns 413 when file size exceeds configured avatar.max-file-size-bytes")
    void uploadRejectsOversizedFile() throws Exception {
        // 6MB > default 5MB limit
        final byte[] oversized = new byte[6 * 1024 * 1024];
        // Add PNG magic so the validator reaches the size check (validation order: empty → size → type)
        oversized[0] = (byte) 0x89;
        oversized[1] = 0x50;
        oversized[2] = 0x4E;
        oversized[3] = 0x47;
        oversized[4] = 0x0D;
        oversized[5] = 0x0A;
        oversized[6] = 0x1A;
        oversized[7] = 0x0A;

        final MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", oversized);

        mockMvc.perform(multipart("/api/v1/avatars/me")
                        .file(file)
                        .with(request -> { request.setMethod("PUT"); return request; })
                        .header(HttpHeaders.AUTHORIZATION, authHeader))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    @DisplayName("Second DELETE after first is idempotent — returns 204 without errors")
    void deleteIsIdempotent() throws Exception {
        // Upload and delete once
        mockMvc.perform(multipart("/api/v1/avatars/me")
                        .file(pngFile(512))
                        .with(request -> { request.setMethod("PUT"); return request; })
                        .header(HttpHeaders.AUTHORIZATION, authHeader))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/avatars/me")
                        .header(HttpHeaders.AUTHORIZATION, authHeader))
                .andExpect(status().isNoContent());

        // Second delete — must not throw 404 or 500
        mockMvc.perform(delete("/api/v1/avatars/me")
                        .header(HttpHeaders.AUTHORIZATION, authHeader))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("GET /me returns hasAvatar=false with HTTP 200 when no avatar has been uploaded")
    void getAvatarReturnsAbsentResponseWhenNeverUploaded() throws Exception {
        mockMvc.perform(get("/api/v1/avatars/me")
                        .header(HttpHeaders.AUTHORIZATION, authHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAvatar").value(false));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static MockMultipartFile pngFile(final int size) {
        final byte[] data = new byte[Math.max(size, 8)];
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

    private static MockMultipartFile jpegFile(final int size) {
        final byte[] data = new byte[Math.max(size, 6)];
        data[0] = (byte) 0xFF;
        data[1] = (byte) 0xD8;
        data[2] = (byte) 0xFF;
        return new MockMultipartFile("file", "avatar.jpg", "image/jpeg", data);
    }

    /**
     * Lists all object keys in the test bucket by querying MinIO directly.
     * Used to verify post-commit cleanup side-effects.
     */
    private List<String> listMinioObjects() throws Exception {
        final MinioClient minioClient = MinioClient.builder()
                .endpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(MINIO_API_PORT))
                .credentials("minioadmin", "minioadmin")
                .build();

        final Iterable<Result<Item>> objects = minioClient.listObjects(
                ListObjectsArgs.builder().bucket(TEST_BUCKET).recursive(true).build());

        final List<String> keys = new ArrayList<>();
        for (final Result<Item> result : objects) {
            keys.add(result.get().objectName());
        }
        return keys;
    }
}
