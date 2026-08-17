package com.example.minio.integration.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Generates self-signed RS256 JWT tokens for integration tests.
 *
 * The public key is exposed as a JWKS JSON that WireMock serves on the
 * Keycloak JWKS endpoint — Spring Security will fetch and cache it to verify tokens.
 */
public class JwtTokenFactory {

    private final RSAKey rsaKey;

    public JwtTokenFactory() {
        try {
            rsaKey = new RSAKeyGenerator(2048)
                    .keyID("test-key-id")
                    .generate();
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to generate RSA key for JWT signing", e);
        }
    }

    /**
     * Returns the JWKS JSON string containing the public key.
     * WireMock exposes this under the Keycloak JWKS path.
     */
    public String jwksJson() {
        try {
            return "{\"keys\":[" + rsaKey.toPublicJWK().toJSONString() + "]}";
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to serialize JWKS", e);
        }
    }

    /**
     * Creates a signed JWT with the given subject, issuer, audience, and Keycloak-style roles claim.
     */
    public String createToken(
            final String subject,
            final String issuer,
            final String audience,
            final List<String> roles) {
        try {
            final JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(issuer)
                    .audience(audience)
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(new Date())
                    .expirationTime(new Date(System.currentTimeMillis() + 3_600_000L))
                    .claim("roles", roles)
                    .build();

            final SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(rsaKey.getKeyID())
                            .build(),
                    claims);

            jwt.sign(new RSASSASigner(rsaKey));
            return jwt.serialize();
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to create test JWT token", e);
        }
    }

    /**
     * Creates a JWT with a past expiration time for negative security tests.
     */
    public String createExpiredToken(final String subject, final String issuer, final String audience) {
        try {
            final JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(issuer)
                    .audience(audience)
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(new Date(System.currentTimeMillis() - 7_200_000L))
                    .expirationTime(new Date(System.currentTimeMillis() - 3_600_000L))
                    .claim("roles", List.of("USER"))
                    .build();

            final SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(rsaKey.getKeyID())
                            .build(),
                    claims);

            jwt.sign(new RSASSASigner(rsaKey));
            return jwt.serialize();
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to create expired test JWT token", e);
        }
    }
}
