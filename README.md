# Storage Service

The service stores only authenticated users' profile images. It uses MinIO for image bytes and PostgreSQL for owner-bound metadata.

## Avatar API

All endpoints require `Authorization: Bearer <Keycloak access token>` with the `USER` role. The owner is always derived from the verified token's `sub`; no endpoint accepts a user ID, object key, or bucket name.

| Method | Path | Result |
| --- | --- | --- |
| `GET` | `/api/v1/avatars/me` | Current avatar metadata, including `hasAvatar` |
| `GET` | `/api/v1/avatars/me/content` | Current avatar bytes |
| `PUT` | `/api/v1/avatars/me` | Create or replace avatar; request is `multipart/form-data` with `file` |
| `DELETE` | `/api/v1/avatars/me` | Delete current avatar |

Only JPEG, PNG, and WebP signatures are accepted. The default size limit is 5 MB. Images are delivered through the authenticated content endpoint, not a public or presigned URL.

## Keycloak configuration

Set these variables in `.env` before running Docker Compose:

```dotenv
KC_ISSUER_URI=https://localhost:8443/realms/mail-and-media-shop-realm
KC_CLIENT_ID=mail-and-media-shop-app
FRONTEND_ORIGIN=http://localhost:5173
```

`KC_ISSUER_URI` must exactly match the `iss` claim in Keycloak access tokens. If the container needs a separately reachable JWKS endpoint, set `KC_JWK_SET_URI` to that endpoint while keeping `KC_ISSUER_URI` unchanged. The storage container must trust the Keycloak TLS certificate.

## Running locally

```bash
docker compose up -d --build
```

Swagger UI is available at `http://localhost:8080/swagger-ui/index.html`; the MinIO console is available at `http://localhost:9001`.
