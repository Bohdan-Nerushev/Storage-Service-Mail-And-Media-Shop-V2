package com.example.minio.integration;

import com.example.minio.integration.support.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests verifying that security rules defined in {@link com.example.minio.config.SecurityConfig}
 * are enforced correctly at the HTTP layer with a real Spring Security filter chain.
 * <p>
 * Naming convention: *IT.java → picked up by maven-failsafe-plugin, not by maven-surefire-plugin.
 */
class SecurityIT extends BaseIntegrationTest {

    // -------------------------------------------------------------------------
    // Happy paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Authenticated user with USER role can access protected endpoints")
    void authenticatedUserWithUserRoleCanAccessEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/avatars/me")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken("integration-user-01")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /actuator/health is publicly accessible without JWT")
    void actuatorHealthIsPubliclyAccessible() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // Negative paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Request without JWT is rejected with 401 Unauthorized")
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/avatars/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Expired JWT token is rejected with 401 Unauthorized")
    void expiredTokenIsRejected() throws Exception {
        final String issuer = "http://localhost:" + WIREMOCK.port() + "/realms/test";
        final String expiredToken = "Bearer " + JWT_FACTORY.createExpiredToken(
                "expired-user", issuer, "mail-and-media-shop-app");

        mockMvc.perform(get("/api/v1/avatars/me")
                        .header(HttpHeaders.AUTHORIZATION, expiredToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("JWT with wrong audience is rejected with 401 Unauthorized")
    void wrongAudienceTokenIsRejected() throws Exception {
        final String issuer = "http://localhost:" + WIREMOCK.port() + "/realms/test";
        final String wrongAudienceToken = "Bearer " + JWT_FACTORY.createToken(
                "some-user", issuer, "completely-different-audience", java.util.List.of("USER"));

        mockMvc.perform(get("/api/v1/avatars/me")
                        .header(HttpHeaders.AUTHORIZATION, wrongAudienceToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Valid JWT without USER role is rejected with 403 Forbidden")
    void missingUserRoleIsRejected() throws Exception {
        final String issuer = "http://localhost:" + WIREMOCK.port() + "/realms/test";
        final String noRoleToken = "Bearer " + JWT_FACTORY.createToken(
                "role-less-user", issuer, "mail-and-media-shop-app", java.util.List.of());

        mockMvc.perform(get("/api/v1/avatars/me")
                        .header(HttpHeaders.AUTHORIZATION, noRoleToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("OPTIONS preflight request is allowed without JWT (CORS support)")
    void optionsPreflightIsAlwaysAllowed() throws Exception {
        mockMvc.perform(options("/api/v1/avatars/me")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT"))
                .andExpect(status().isOk());
    }
}
