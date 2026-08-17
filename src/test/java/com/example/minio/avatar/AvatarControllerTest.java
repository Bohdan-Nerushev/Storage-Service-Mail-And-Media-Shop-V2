package com.example.minio.avatar;

import com.example.minio.dto.AvatarContent;
import com.example.minio.dto.AvatarResponse;
import com.example.minio.exception.AvatarValidationException;
import com.example.minio.exception.FileStorageException;
import com.example.minio.exception.ResourceNotFoundException;
import com.example.minio.service.AvatarService;
import com.example.minio.service.MinioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice-equivalent test for {@link com.example.minio.controller.AvatarController}.
 *
 * Spring Boot 4.x removed @WebMvcTest and @AutoConfigureMockMvc.
 * MockMvc is configured manually via MockMvcBuilders.webAppContextSetup() with
 * springSecurity() apply so that the full security filter chain is active.
 *
 * Covers: authentication/authorisation enforcement, HTTP status codes, response headers,
 * and delegation to AvatarService.
 */
@SpringBootTest
class AvatarControllerTest {

    @Autowired
    private WebApplicationContext context;

    // Built in @BeforeEach — @AutoConfigureMockMvc was removed in Spring Boot 4.x
    private MockMvc mockMvc;

    @MockitoBean
    private AvatarService avatarService;

    // Prevents MinioInitializer from attempting a real connection on context startup
    @MockitoBean
    private MinioService minioService;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    // Convenience factory — creates a JWT post-processor with the USER role
    private static RequestPostProcessor userJwt(final String subject) {
        return jwt()
                .jwt(j -> j.subject(subject).claim("roles", List.of("USER")))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/avatars/me
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /me → 200 with AvatarResponse payload for authenticated USER")
    void getAvatarReturns200WithPayload() throws Exception {
        when(avatarService.getAvatar(anyString()))
                .thenReturn(new AvatarResponse(true, 1L, "image/jpeg", 1024L, Instant.now()));

        mockMvc.perform(get("/api/v1/avatars/me").with(userJwt("user-sub-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAvatar").value(true))
                .andExpect(jsonPath("$.contentType").value("image/jpeg"));
    }

    @Test
    @DisplayName("GET /me → 401 when request has no JWT")
    void getAvatarReturns401WhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/avatars/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /me → 403 when JWT lacks the USER role")
    void getAvatarReturns403WhenWrongRole() throws Exception {
        mockMvc.perform(get("/api/v1/avatars/me")
                        .with(jwt()
                                .jwt(j -> j.subject("guest-sub-001").claim("roles", List.of("GUEST")))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_GUEST"))))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/avatars/me/content
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /me/content → 200 with Cache-Control, ETag and Vary headers")
    void getAvatarContentReturns200WithCacheHeaders() throws Exception {
        final AvatarContent content = new AvatarContent(
                new ByteArrayInputStream(new byte[]{1, 2, 3}),
                "image/jpeg",
                3L,
                5L);
        when(avatarService.getContent(anyString())).thenReturn(content);

        mockMvc.perform(get("/api/v1/avatars/me/content").with(userJwt("user-sub-001")))
                .andExpect(status().isOk())
                .andExpect(header().exists("Cache-Control"))
                .andExpect(header().string("ETag", "\"5\""))
                .andExpect(header().stringValues("Vary", hasItem("Authorization")));
    }

    @Test
    @DisplayName("GET /me/content → 404 when no avatar has been uploaded")
    void getAvatarContentReturns404WhenNoAvatar() throws Exception {
        when(avatarService.getContent(anyString()))
                .thenThrow(new ResourceNotFoundException("Avatar not found for the authenticated user."));

        mockMvc.perform(get("/api/v1/avatars/me/content").with(userJwt("user-sub-001")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /me/content → 500 when MinIO download fails")
    void getAvatarContentReturns500WhenMinioFails() throws Exception {
        when(avatarService.getContent(anyString()))
                .thenThrow(new FileStorageException("minio down", new RuntimeException()));

        mockMvc.perform(get("/api/v1/avatars/me/content").with(userJwt("user-sub-001")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));
    }

    // -------------------------------------------------------------------------
    // PUT /api/v1/avatars/me
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PUT /me → 200 with updated AvatarResponse after successful upload")
    void replaceAvatarReturns200WithMetadata() throws Exception {
        when(avatarService.replaceAvatar(anyString(), any()))
                .thenReturn(new AvatarResponse(true, 2L, "image/png", 512L, Instant.now()));

        final MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        mockMvc.perform(multipart("/api/v1/avatars/me")
                        .file(file)
                        .with(request -> { request.setMethod("PUT"); return request; })
                        .with(userJwt("user-sub-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAvatar").value(true))
                .andExpect(jsonPath("$.contentType").value("image/png"));
    }

    @Test
    @DisplayName("PUT /me → 415 when service rejects the file type")
    void replaceAvatarReturns415WhenInvalidMimeType() throws Exception {
        when(avatarService.replaceAvatar(anyString(), any()))
                .thenThrow(new AvatarValidationException(
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Only JPEG, PNG, and WebP avatar images are allowed."));

        final MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", "not an image".getBytes());

        mockMvc.perform(multipart("/api/v1/avatars/me")
                        .file(file)
                        .with(request -> { request.setMethod("PUT"); return request; })
                        .with(userJwt("user-sub-001")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    @DisplayName("PUT /me → 413 when service rejects the file because it is too large")
    void replaceAvatarReturns413WhenFileTooLarge() throws Exception {
        when(avatarService.replaceAvatar(anyString(), any()))
                .thenThrow(new AvatarValidationException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "Avatar file exceeds the allowed size."));

        final MockMultipartFile file = new MockMultipartFile(
                "file", "big.png", "image/png", new byte[10]);

        mockMvc.perform(multipart("/api/v1/avatars/me")
                        .file(file)
                        .with(request -> { request.setMethod("PUT"); return request; })
                        .with(userJwt("user-sub-001")))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413));
    }

    // -------------------------------------------------------------------------
    // DELETE /api/v1/avatars/me
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("DELETE /me → 204 No Content on successful deletion")
    void deleteAvatarReturns204() throws Exception {
        doNothing().when(avatarService).deleteAvatar(anyString());

        mockMvc.perform(delete("/api/v1/avatars/me").with(userJwt("user-sub-001")))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /me → 401 when no JWT is provided")
    void deleteAvatarReturns401WhenUnauthenticated() throws Exception {
        mockMvc.perform(delete("/api/v1/avatars/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /me → 403 when JWT lacks the USER role")
    void deleteAvatarReturns403WhenWrongRole() throws Exception {
        mockMvc.perform(delete("/api/v1/avatars/me")
                        .with(jwt()
                                .jwt(j -> j.subject("user-sub-001").claim("roles", List.of()))
                                .authorities(List.of())))
                .andExpect(status().isForbidden());
    }
}
