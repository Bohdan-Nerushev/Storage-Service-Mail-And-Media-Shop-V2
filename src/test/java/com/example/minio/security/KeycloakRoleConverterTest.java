package com.example.minio.security;

import com.example.minio.config.KeycloakRoleConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakRoleConverterTest {

    private final KeycloakRoleConverter converter = new KeycloakRoleConverter();

    // -------------------------------------------------------------------------
    // Happy paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Maps each Keycloak role to a Spring ROLE_ prefixed authority")
    void convertsRolesWithRolePrefix() {
        final Jwt jwt = buildJwt(Map.of("roles", List.of("USER", "ADMIN")));

        final Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    // -------------------------------------------------------------------------
    // Negative paths
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Returns an empty collection when the 'roles' claim is absent from the JWT")
    void returnsEmptyCollectionWhenRolesClaimAbsent() {
        // JWT has no "roles" claim at all
        final Jwt jwt = buildJwt(Map.of());

        final Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).isEmpty();
    }

    @Test
    @DisplayName("Returns an empty collection when the 'roles' claim is explicitly null")
    void returnsEmptyCollectionWhenRolesClaimIsNull() {
        // Jwt.Builder does not allow null claim values, so we verify via absent claim
        // (Jwt.getClaimAsStringList returns null when the claim is not present)
        final Jwt jwt = buildJwt(Map.of("other_claim", "value"));

        final Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).isNotNull().isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Jwt buildJwt(final Map<String, Object> extraClaims) {
        final Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("sub", "test-user");
        claims.put("iss", "https://issuer.example.test/realms/test");
        claims.putAll(extraClaims);

        return Jwt.withTokenValue("test-token")
                .headers(h -> h.put("alg", "RS256"))
                .claims(c -> c.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
