package com.example.minio.exception;

import com.example.minio.dto.ErrorResponse;
import com.example.minio.service.AvatarService;
import com.example.minio.service.MinioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that {@link GlobalExceptionHandler} maps each exception type to the correct HTTP status
 * and produces a valid {@link ErrorResponse} JSON body.
 * <p>
 * Uses AvatarController as a probe endpoint; the controller itself is not under test here.
 * Spring Boot 4.x removed @AutoConfigureMockMvc — MockMvc is configured manually.
 */
@SpringBootTest
class GlobalExceptionHandlerTest {

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
    private static org.springframework.test.web.servlet.request.RequestPostProcessor userJwt(
            final String subject) {
        return jwt()
                .jwt(j -> j.subject(subject).claim("roles", java.util.List.of("USER")))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"));
    }

    // -------------------------------------------------------------------------
    // Happy paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ResourceNotFoundException → HTTP 404 with ErrorResponse body")
    void handlesResourceNotFoundException() throws Exception {
        when(avatarService.getAvatar(anyString()))
                .thenThrow(new ResourceNotFoundException("Avatar not found for the authenticated user."));

        mockMvc.perform(get("/api/v1/avatars/me").with(userJwt("user-sub-001")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Avatar not found for the authenticated user."))
                .andExpect(jsonPath("$.path").value("/api/v1/avatars/me"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("AvatarValidationException with dynamic status → HTTP status taken from the exception")
    void handlesAvatarValidationExceptionWithDynamicStatus() throws Exception {
        when(avatarService.getAvatar(anyString()))
                .thenThrow(new AvatarValidationException(
                        org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Only JPEG, PNG, and WebP avatar images are allowed."));

        mockMvc.perform(get("/api/v1/avatars/me").with(userJwt("user-sub-001")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    // -------------------------------------------------------------------------
    // Negative paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("FileStorageException → HTTP 500 with generic Internal Server Error body")
    void handlesFileStorageExceptionAs500() throws Exception {
        when(avatarService.getAvatar(anyString()))
                .thenThrow(new FileStorageException("MinIO is unreachable", new RuntimeException()));

        mockMvc.perform(get("/api/v1/avatars/me").with(userJwt("user-sub-001")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"));
    }

    @Test
    @DisplayName("Unhandled RuntimeException → HTTP 500 with safe message, no stack trace exposed")
    void handlesGenericExceptionAs500WithSafeMessage() throws Exception {
        when(avatarService.getAvatar(anyString()))
                .thenThrow(new RuntimeException("NullPointerException at line 42"));

        mockMvc.perform(get("/api/v1/avatars/me").with(userJwt("user-sub-001")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                // Body must NOT expose internal implementation details
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @Test
    @DisplayName("MaxUploadSizeExceededException → HTTP 413 Payload Too Large")
    void handlesMaxUploadSizeExceededAs413() throws Exception {
        when(avatarService.getAvatar(anyString()))
                .thenThrow(new MaxUploadSizeExceededException(5 * 1024 * 1024L));

        mockMvc.perform(get("/api/v1/avatars/me").with(userJwt("user-sub-001")))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.message").value("Avatar file exceeds the allowed size."));
    }
}
