#!/bin/bash
# Safe environment variable loader that respects existing environment variables (CI/CD)

load_env_safe() {
    local env_file="$1"
    if [ -f "$env_file" ]; then
        log_info "Loading environment variables from $(basename "$env_file") (safe mode)"
        while IFS='=' read -r key value || [ -n "$key" ]; do
            # Skip comments and empty lines
            [[ "$key" =~ ^#.*$ ]] && continue
            [[ -z "$key" ]] && continue
            
            # Trim possible whitespace
            key=$(echo "$key" | xargs)
            value=$(echo "$value" | xargs)
            
            # Export if the variable is NOT set OR if it is EMPTY
            if [ -z "${!key+x}" ] || [ -z "${!key}" ]; then
                export "$key=$value"
            else
                log_info "Skipping $key: already set in environment (CI/CD priority)"
            fi
        done < "$env_file"
    fi
}

# Run it for the project root .env
if [ -n "$PROJECT_ROOT" ]; then
    if [ ! -f "$PROJECT_ROOT/.env" ] && [ -f "$PROJECT_ROOT/.env.example" ]; then
        log_info "No .env file found. Creating .env from .env.example"
        cp "$PROJECT_ROOT/.env.example" "$PROJECT_ROOT/.env" 2>/dev/null || true
    fi
    load_env_safe "$PROJECT_ROOT/.env"
else
    if [ ! -f ".env" ] && [ -f ".env.example" ]; then
        log_info "No .env file found. Creating .env from .env.example"
        cp ".env.example" ".env" 2>/dev/null || true
    fi
    load_env_safe ".env"
fi

export SSH_CMD="ssh ${SSH_USER}@${SSH_HOST} -o StrictHostKeyChecking=no"
export SCP_CMD="scp -o StrictHostKeyChecking=no -r helm/storage-service ${SSH_USER}@${SSH_HOST}:~/deployments/"

