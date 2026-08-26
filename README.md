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
KC_CLIENT_ID=storage-service-app
FRONTEND_ORIGIN=http://localhost:5173
```

`KC_ISSUER_URI` must exactly match the `iss` claim in Keycloak access tokens. If the container needs a separately reachable JWKS endpoint, set `KC_JWK_SET_URI` to that endpoint while keeping `KC_ISSUER_URI` unchanged. The storage container must trust the Keycloak TLS certificate.

## Running locally

```bash
docker compose up -d --build
```

Swagger UI is available at `http://localhost:8080/swagger-ui/index.html`; the MinIO console is available at `http://localhost:9001`.

## End-to-End (E2E) Tests

Complete end-to-end tests for all avatar API endpoints are provided in the `e2e_tests/` directory. These tests cover:

- All 4 API endpoints (GET /me, GET /me/content, PUT /me, DELETE /me)
- Positive scenarios (successful operations with valid data)
- Negative scenarios (error handling for invalid inputs)
- Authentication tests (unauthorized access)
- Image format validation (JPEG, PNG, WebP)
- File size validation (exceeds 5 MB limit)
- Idempotent operations (DELETE returns 204 even if no avatar exists)

### Prerequisites

Before running E2E tests, ensure:

1. **Storage Service is running:**
   ```bash
   docker compose up -d --build
   ```

2. **Keycloak is running** with the test realm and user created.
   
   For independent testing without depending on the API service's Keycloak:
   ```bash
   cd scripts/
   docker compose up -d --build
   cd ..
   ```
   
   This will start a separate Keycloak instance configured for E2E testing.

3. **Environment variables are set:** Copy `.env.example` to `.env` and configure:
   ```bash
   cp .env.example .env
   ```

   Key variables for E2E tests:
   ```dotenv
   # Storage Service
   STORAGE_APP_HOST=http://localhost
   STORAGE_APP_PORT=8080
   
   # Keycloak
   KC_URL=https://localhost:8443
   KC_REALM=mail-and-media-shop-realm
   KC_CLIENT_ID=storage-service-app
   KC_CLIENT_SECRET=
   KC_ADMIN_USER=
   KC_ADMIN_PASS=
   USER_EMAIL=
   USER_PASSWORD=
   ```

### Running E2E Tests

#### Linux / macOS (bash/zsh):

```bash
cd e2e_tests/
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
python3 main.py
deactivate
cd ..
```

### Test Coverage

The E2E test suite includes **18 test scenarios**:

**GET /api/v1/avatars/me** (3 tests)
- ✓ Get avatar metadata when avatar exists
- ✓ Get avatar metadata when no avatar exists
- ✓ Unauthorized access (no token)

**GET /api/v1/avatars/me/content** (3 tests)
- ✓ Download avatar binary content (with cache headers)
- ✓ 404 when avatar not found
- ✓ Unauthorized access (no token)

**PUT /api/v1/avatars/me** (7 tests)
- ✓ Upload valid JPEG image
- ✓ Upload valid PNG image
- ✓ Upload valid WebP image
- ✗ 400 when uploading empty file
- ✗ 415 when uploading unsupported media type
- ✗ 413 when file exceeds size limit
- ✗ 415 when image signature is truncated/incomplete
- ✓ Unauthorized access (no token)

**DELETE /api/v1/avatars/me** (4 tests)
- ✓ Delete existing avatar (204 No Content)
- ✓ Delete non-existent avatar (idempotent, returns 204)
- ✓ Unauthorized access (no token)

### Test Execution Flow

1. **Setup:** Clean previous test data, obtain Keycloak token
2. **Test GET /me:** Verify behavior when no avatar exists
3. **Test PUT /me:** Upload JPEG, PNG, WebP images; test error scenarios
4. **Test GET /me/content:** Download avatar binary with cache headers
5. **Test DELETE /me:** Delete avatar, verify idempotency
6. **Cleanup:** Remove test avatar and user from Keycloak

---

## GitLab CI/CD Variables Automation

The project includes an automated script [`scripts/upload_gitlab_variables.sh`](file:///home/bnerushev/Schreibtisch/Project/ALL-mail-and-media-shop-v2/storage-service-mail-and-media-shop-v2/scripts/upload_gitlab_variables.sh) to sync environment variables from your local `.env` file directly to GitLab CI/CD Variables via the GitLab REST API (`https://git.mam.dev/api/v4/projects`).

### Prerequisites
1. A valid `.env` file in the project root directory. If `.env` is missing, the script will abort with an error message:
   ```text
   ERROR: .env file not found (.env). Please create a .env file before running this script.
   ```
2. A GitLab Personal Access Token with `api` scope generated at [https://git.mam.dev/-/profile/personal_access_tokens](https://git.mam.dev/-/profile/personal_access_tokens).

### Usage

Run the script by providing your `GITLAB_TOKEN`:

```bash
GITLAB_TOKEN=your_personal_access_token ./scripts/upload_gitlab_variables.sh
```

### Features
* **Dynamic `.env` Parsing**: Automatically reads all non-empty key-value pairs from `.env` (skipping comments and empty lines).
* **Idempotent Execution**: Attempts to create each variable (`POST`). If the variable already exists in GitLab, it updates its value (`PUT`).
* **Environment Overrides**: Supports overriding `GITLAB_URL` (default: `https://git.mam.dev`) and `PROJECT_PATH` (default: `bnerushev%2Fstorage-service-mail-and-media-shop-v2`).

---

## GitHub Actions Secrets Automation

The project includes an automated script [`scripts/upload_github_secrets.sh`](file:///home/bnerushev/Schreibtisch/Project/ALL-mail-and-media-shop-v2/storage-service-mail-and-media-shop-v2/scripts/upload_github_secrets.sh) to upload necessary repository secrets directly to GitHub using the GitHub CLI (`gh`). By default, it loads secrets from the external `/home/bnerushev/PycharmProjects/MailServiceAPI/.env` file.

### Prerequisites
1. A valid `.env` file at `/home/bnerushev/PycharmProjects/MailServiceAPI/.env`.
2. GitHub CLI (`gh`) installed on your system.
3. Authenticated GitHub session (`gh auth login`).

### Usage

1. Check if `gh` is installed:
   ```bash
   gh --version
   ```
2. Log in to your GitHub account:
   ```bash
   gh auth login
   ```
3. Run the upload script:
   ```bash
   ./scripts/upload_github_secrets.sh
   ```

### Features
* **Filtered Secret Upload**: Uploads only the allowed keys required by the workflows or MailServiceAPI, ignoring auxiliary and monitoring variables to save GitHub secrets quota.
* **Non-empty validation**: Automatically skips empty configuration keys.
