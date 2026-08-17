#!/bin/bash
# ==============================================================================
# Script: sync_env_to_vault.sh
# Purpose: Syncs local .env variables to the remote HashiCorp Vault instance.
#          It automatically unseals Vault using the keys found in the .env file.
# ==============================================================================
set -euo pipefail

# Configuration
ENV_FILE=".env"
VAULT_PORT="8200"

log_info() { echo -e "\e[32m[INFO]\e[0m $*"; }
log_warn() { echo -e "\e[33m[WARN]\e[0m $*"; }
log_error() { echo -e "\e[31m[ERROR]\e[0m $*"; exit 1; }

# 1. Verify local .env file exists
if [ ! -f "$ENV_FILE" ]; then
    log_error "The environment file '$ENV_FILE' does not exist."
fi

log_info "Parsing Vault configuration and credentials from $ENV_FILE..."

# 2. Extract SSH details
SSH_USER=$(grep -E "^SSH_USER=" "$ENV_FILE" | cut -d'=' -f2- | xargs)
SSH_HOST=$(grep -E "^SSH_HOST=" "$ENV_FILE" | cut -d'=' -f2- | xargs)

if [ -z "$SSH_USER" ] || [ -z "$SSH_HOST" ]; then
    log_error "SSH_USER or SSH_HOST not defined in $ENV_FILE."
fi

# 3. Extract Unseal Keys and Token from comments/lines
KEY1=$(grep -i "Unseal Key 1" "$ENV_FILE" | cut -d':' -f2- | xargs)
KEY2=$(grep -i "Unseal Key 2" "$ENV_FILE" | cut -d':' -f2- | xargs)
KEY3=$(grep -i "Unseal Key 3" "$ENV_FILE" | cut -d':' -f2- | xargs)
ROOT_TOKEN=$(grep -i "Root Token" "$ENV_FILE" | cut -d':' -f2- | xargs)

if [ -z "$KEY1" ] || [ -z "$KEY2" ] || [ -z "$KEY3" ]; then
    log_error "Failed to find all three Vault Unseal Keys in $ENV_FILE."
fi

if [ -z "$ROOT_TOKEN" ]; then
    log_error "Failed to find Vault Root Token in $ENV_FILE."
fi

log_info "Vault details successfully extracted from $ENV_FILE."
log_info "Connecting to remote server $SSH_USER@$SSH_HOST to verify Vault status..."

# 4. Define SSH command
SSH_CMD="ssh -o StrictHostKeyChecking=no ${SSH_USER}@${SSH_HOST}"

# 5. Check if Vault is sealed and unseal if necessary
SEALED_STATUS=$($SSH_CMD "VAULT_ADDR=https://127.0.0.1:$VAULT_PORT vault status -tls-skip-verify -format=json 2>/dev/null | grep -o '\"sealed\":[^,]*' | cut -d':' -f2" || echo "unknown")

if [ "$SEALED_STATUS" = "true" ]; then
    log_warn "Vault is currently SEALED. Attempting to unseal..."
    $SSH_CMD "VAULT_ADDR=https://127.0.0.1:$VAULT_PORT vault operator unseal -tls-skip-verify $KEY1 > /dev/null"
    $SSH_CMD "VAULT_ADDR=https://127.0.0.1:$VAULT_PORT vault operator unseal -tls-skip-verify $KEY2 > /dev/null"
    log_info "Vault unseal commands executed with Key 1 and Key 2."
    
    # Verify unsealed
    SEALED_STATUS_AFTER=$($SSH_CMD "VAULT_ADDR=https://127.0.0.1:$VAULT_PORT vault status -tls-skip-verify -format=json 2>/dev/null | grep -o '\"sealed\":[^,]*' | cut -d':' -f2" || echo "unknown")
    if [ "$SEALED_STATUS_AFTER" = "false" ]; then
        log_info "Vault successfully UNSEALED."
    else
        log_error "Failed to unseal Vault. Please check your unseal keys."
    fi
elif [ "$SEALED_STATUS" = "false" ]; then
    log_info "Vault is already UNSEALED."
else
    log_warn "Could not read Vault seal status (Vault might be stopped). Checking systemd service..."
    $SSH_CMD "sudo systemctl is-active vault > /dev/null" || log_error "Vault service is NOT running on the server. Run 'sudo systemctl start vault' first."
fi

# 6. Build the 'vault kv put' command from .env variables
log_info "Preparing keys and values from $ENV_FILE to sync to Vault..."

# We generate a temporary script to be run on the remote host to avoid SSH escaping issues
TEMP_UPLOAD_SCRIPT="/tmp/vault_sync_data.sh"

cat << 'EOF' > "$TEMP_UPLOAD_SCRIPT"
#!/bin/bash
set -euo pipefail
export VAULT_ADDR="https://127.0.0.1:8200"

# Check if kv-v2 engine is enabled at secret/
if ! vault secrets list -tls-skip-verify | grep -q "^secret/"; then
    echo "Enabling kv-v2 secrets engine at path 'secret/'..."
    vault secrets enable -tls-skip-verify -path=secret kv-v2
fi

# Log in using root token
vault login -tls-skip-verify "$1" >/dev/null

echo "Writing secrets to Vault at path 'secret/mam-shop/dev'..."
vault kv put -tls-skip-verify secret/mam-shop/dev \
EOF

# Parse variables from local .env and append them as arguments to the temp script
while IFS='=' read -r key value || [ -n "$key" ]; do
    # Trim spaces
    key=$(echo "$key" | xargs)
    # Skip comments, blank lines, SSH variables, and Vault credentials themselves
    [[ "$key" =~ ^#.*$ ]] && continue
    [[ -z "$key" ]] && continue
    [[ "$key" == "SSH_USER" ]] && continue
    [[ "$key" == "SSH_HOST" ]] && continue
    [[ "$key" =~ ^UNSEAL_KEY_.*$ ]] && continue
    [[ "$key" == "VAULT_TOKEN" ]] && continue

    # Escape double quotes in value
    escaped_value=$(echo "$value" | sed 's/"/\\"/g')
    echo "  $key=\"$escaped_value\" \\" >> "$TEMP_UPLOAD_SCRIPT"
done < "$ENV_FILE"

# Remove the trailing backslash from the last line and replace it
sed -i '$ s/ \\$//' "$TEMP_UPLOAD_SCRIPT"

log_info "Copying upload script to remote server..."
scp -o StrictHostKeyChecking=no "$TEMP_UPLOAD_SCRIPT" "${SSH_USER}@${SSH_HOST}:/tmp/vault_sync_data.sh"

log_info "Executing upload script on remote server..."
$SSH_CMD "bash /tmp/vault_sync_data.sh '$ROOT_TOKEN'"

log_info "Cleaning up temporary files..."
rm -f "$TEMP_UPLOAD_SCRIPT"
$SSH_CMD "rm -f /tmp/vault_sync_data.sh"

log_info "Vault synchronization completed successfully!"
