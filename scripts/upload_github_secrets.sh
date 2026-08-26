#!/bin/bash
# ==============================================================================
# Script to upload GitHub Secrets from local .env via GitHub CLI (gh)
# Defaults to reading from /home/bnerushev/PycharmProjects/MailServiceAPI/.env
# ==============================================================================
set -e

ENV_FILE="/home/bnerushev/PycharmProjects/MailServiceAPI/.env"

# Allow overriding the ENV_FILE path via arguments
if [ -n "$1" ]; then
    ENV_FILE="$1"
fi

if [ ! -f "$ENV_FILE" ]; then
    echo "ERROR: .env file not found ($ENV_FILE). Please create a .env file before running this script."
    exit 1
fi

# Check if GitHub CLI is installed
if ! command -v gh &> /dev/null; then
    echo "ERROR: GitHub CLI ('gh') is not installed."
    echo "Please install it from https://cli.github.com/ and login using 'gh auth login'."
    exit 1
fi

# Check if authenticated
if ! gh auth status &>/dev/null; then
    echo "ERROR: You are not authenticated with GitHub CLI."
    echo "Please run 'gh auth login' to authenticate."
    exit 1
fi

echo "Reading secrets from: $ENV_FILE..."

# List of secrets we actually want to upload
ALLOWED_KEYS=(
  # --- MailServiceAPI Env Keys ---
  "SMTP_SERVER" "SMTP_PORT" "SMTP_USER" "SMTP_PASSWORD"
  "IMAP_SERVER" "IMAP_PORT" "IMAP_USER" "IMAP_PASSWORD"
  "APP_PORT" "DEBUG" "DOMAIN" "SUDO_USER_PASSWORD"
  "GRAFANA_ADMIN_USER" "GRAFANA_ADMIN_PASSWORD"

  # --- Storage Service Env Keys ---
  "KC_ISSUER_URI" "KC_JWK_SET_URI" "KC_CLIENT_ID" "FRONTEND_ORIGIN"
  "AVATAR_MAX_FILE_SIZE" "AVATAR_MAX_REQUEST_SIZE" "KEYCLOAK_PORT" "KEYCLOAK_HTTPS_PORT"
  "KC_URL" "KC_REALM" "KC_BOOTSTRAP_ADMIN_USERNAME" "KC_BOOTSTRAP_ADMIN_PASSWORD"
  "KC_ADMIN_USER" "KC_ADMIN_PASS" "KC_CLIENT_SECRET" "KC_GRANT_TYPE" "KC_USERNAME" "KC_PASSWORD"
  "MINIO_ROOT_USER" "MINIO_ROOT_PASSWORD" "POSTGRES_USER" "POSTGRES_PASSWORD" "TRUSTSTORE_PASSWORD"
  "USER_EMAIL" "USER_PASSWORD" "STORAGE_APP_HOST" "STORAGE_APP_PORT"
  "KEYCLOAK_DB_PORT" "KEYCLOAK_DB_USER" "KEYCLOAK_DB_PASSWORD" "KEYCLOAK_DB_NAME" "KEYCLOAK_DB_IMAGE_VERSION"
  "MONITORING_NETWORK_NAME"
)

# Helper to check if array contains element
contains_element() {
  local e match="$1"
  shift
  for e; do [[ "$e" == "$match" ]] && return 0; done
  return 1
}

while IFS= read -r line || [ -n "$line" ]; do
    # Strip leading/trailing whitespace
    line=$(echo "$line" | xargs)

    # Skip comments and empty lines
    [[ "$line" =~ ^#.*$ ]] && continue
    [[ -z "$line" ]] && continue
    [[ "$line" != *"="* ]] && continue

    KEY=$(echo "$line" | cut -d'=' -f1 | xargs)
    VALUE=$(echo "$line" | cut -d'=' -f2- | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//")

    # Only upload keys that are allowed
    if contains_element "$KEY" "${ALLOWED_KEYS[@]}"; then
        if [ -z "$VALUE" ]; then
            echo "Skipping $KEY (value is empty)"
            continue
        fi

        echo -n "Uploading secret $KEY... "
        # Run gh secret set
        echo "$VALUE" | gh secret set "$KEY"
        echo "✅ Done"
    fi
done < "$ENV_FILE"

echo "All allowed secrets from $ENV_FILE uploaded successfully to GitHub!"
