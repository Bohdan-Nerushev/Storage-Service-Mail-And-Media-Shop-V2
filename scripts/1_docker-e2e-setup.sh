#!/bin/bash

# ==============================================================================
# PHASE 1: Environment Initialization & Docker Verification
# ==============================================================================
set -o pipefail

# Source common utilities and load environment
source "$(dirname "${BASH_SOURCE[0]}")/utils.sh"
source "$(dirname "${BASH_SOURCE[0]}")/env_loader.sh"

# Helper functions for service health monitoring
wait_for_service_healthy() {
    local compose_file="$1"
    local service_name="$2"
    local timeout_seconds="${3:-120}"
    local start_time=$(date +%s)
    
    log_info "Waiting for service '$service_name' in '$(basename "$compose_file")' to be healthy..."
    
    while true; do
        local container_id=$(docker compose -f "$compose_file" ps -a -q "$service_name" 2>/dev/null)
        if [ -n "$container_id" ]; then
            local status=$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id" 2>/dev/null)
            if [ "$status" = "healthy" ]; then
                log_info "✅ Service '$service_name' is healthy!"
                return 0
            fi
            
            # If the container has exited/failed
            local state=$(docker inspect --format='{{.State.Status}}' "$container_id" 2>/dev/null)
            if [ "$state" = "exited" ]; then
                local exit_code=$(docker inspect --format='{{.State.ExitCode}}' "$container_id" 2>/dev/null)
                log_error "Container logs for '$service_name':"
                docker compose -f "$compose_file" logs "$service_name"
                error_exit "❌ Service '$service_name' exited prematurely with exit code $exit_code."
            fi
        fi
        
        local current_time=$(date +%s)
        local elapsed=$((current_time - start_time))
        if [ "$elapsed" -ge "$timeout_seconds" ]; then
            log_error "Container logs for '$service_name':"
            docker compose -f "$compose_file" logs "$service_name"
            error_exit "Timeout waiting for service '$service_name' to become healthy."
        fi
        
        sleep 2
    done
}

wait_for_service_exit_zero() {
    local compose_file="$1"
    local service_name="$2"
    local timeout_seconds="${3:-180}"
    local start_time=$(date +%s)
    
    log_info "Waiting for service '$service_name' in '$(basename "$compose_file")' to complete successfully..."
    
    while true; do
        local container_id=$(docker compose -f "$compose_file" ps -a -q "$service_name" 2>/dev/null)
        if [ -n "$container_id" ]; then
            local state=$(docker inspect --format='{{.State.Status}}' "$container_id" 2>/dev/null)
            if [ "$state" = "exited" ]; then
                local exit_code=$(docker inspect --format='{{.State.ExitCode}}' "$container_id" 2>/dev/null)
                if [ "$exit_code" = "0" ]; then
                    log_info "✅ Service '$service_name' completed successfully (exit code 0)."
                    return 0
                else
                    error_exit "❌ Service '$service_name' failed with exit code $exit_code."
                fi
            fi
        fi
        
        local current_time=$(date +%s)
        local elapsed=$((current_time - start_time))
        if [ "$elapsed" -ge "$timeout_seconds" ]; then
            error_exit "Timeout waiting for service '$service_name' to complete."
        fi
        
        sleep 2
    done
}

# Configurable Parameters
export APP_PORT=${APP_PORT:-8080}
export APP_HOST="${APP_HOST:-http://localhost}"
export TRUSTSTORE_PASSWORD=${TRUSTSTORE_PASSWORD:-changeit}
export KEYCLOAK_HTTPS_PORT=8444
export KC_URL="https://localhost:8444"
export TRUSTSTORE_PATH="${TRUSTSTORE_PATH:-$PROJECT_ROOT/certs/truststore.jks}"
HEALTH_ENDPOINT="$APP_HOST:$APP_PORT/actuator/health"

# Verify Docker tools
check_dependencies docker curl

# Check if 'docker compose' (plugin) works
if ! docker compose version &> /dev/null; then
    error_exit "Docker Compose plugin is not installed or not working."
fi

# ==============================================================================
# PHASE 2: Cleanup & Container Orchestration
# ==============================================================================

# Ensure monitoring network exists for standalone E2E testing
export MONITORING_NETWORK_NAME="storage-service-mail-and-media-shop-v2_monitoring"
log_info "Ensuring monitoring network '$MONITORING_NETWORK_NAME' exists..."
docker network inspect "$MONITORING_NETWORK_NAME" &>/dev/null || docker network create "$MONITORING_NETWORK_NAME"

# Ensure certs directory exists
mkdir -p "$PROJECT_ROOT/certs"

# If any of the expected certificate/truststore files are directories (due to docker pre-creation bug),
# remove them so we can write files. If root-owned, we try deleting them using a docker run helper.
for cert_item in "keycloak-cert.pem" "keycloak-key.pem" "truststore.jks"; do
    if [ -d "$PROJECT_ROOT/certs/$cert_item" ]; then
        log_warn "certs/$cert_item is a directory. Removing..."
        rm -rf "$PROJECT_ROOT/certs/$cert_item" 2>/dev/null || \
          docker run --rm -v "$PROJECT_ROOT/certs:/certs" alpine rm -rf "/certs/$cert_item" || true
    fi
done

# Touch placeholders to prevent Docker Compose from pre-creating them as root-owned directories during down/up commands
for cert_item in "keycloak-cert.pem" "keycloak-key.pem" "truststore.jks"; do
    if [ ! -e "$PROJECT_ROOT/certs/$cert_item" ]; then
        touch "$PROJECT_ROOT/certs/$cert_item"
    fi
done

log_info "Ensuring clean environment (stopping any existing containers)..."
docker compose -f "$PROJECT_ROOT/scripts/docker-compose.yml" down -v 2>/dev/null || true
docker compose -f "$PROJECT_ROOT/docker-compose.yml" down -v 2>/dev/null || true

# Ensure logs directories exist and are writable by non-root container users
mkdir -p "$PROJECT_ROOT/logs"
chmod 1777 "$PROJECT_ROOT/logs" 2>/dev/null || true

mkdir -p "$PROJECT_ROOT/scripts/logs"
chmod 1777 "$PROJECT_ROOT/scripts/logs" 2>/dev/null || \
  docker run --rm -v "$PROJECT_ROOT/scripts:/scripts" alpine chmod 1777 /scripts/logs || true

if command -v mkcert &> /dev/null; then
    log_info "mkcert detected. Copying root CA to certs/rootCA.pem..."
    cp "$(mkcert -CAROOT)/rootCA.pem" "$PROJECT_ROOT/certs/rootCA.pem" 2>/dev/null || true
fi

if [ ! -f "$PROJECT_ROOT/certs/keycloak-cert.pem" ] || [ ! -s "$PROJECT_ROOT/certs/keycloak-cert.pem" ] || \
   [ ! -f "$PROJECT_ROOT/certs/keycloak-key.pem" ] || [ ! -s "$PROJECT_ROOT/certs/keycloak-key.pem" ]; then
    log_info "Certificates not found or empty. Generating self-signed certificates..."
    rm -f "$PROJECT_ROOT/certs/keycloak-cert.pem" "$PROJECT_ROOT/certs/keycloak-key.pem"
    openssl req -x509 -newkey rsa:4096 -nodes -sha256 \
      -keyout "$PROJECT_ROOT/certs/keycloak-key.pem" \
      -out "$PROJECT_ROOT/certs/keycloak-cert.pem" \
      -subj "/CN=keycloak" \
      -days 365 \
      -addext "basicConstraints=critical,CA:TRUE" \
      -addext "subjectAltName=DNS:keycloak,DNS:localhost,IP:127.0.0.1" || error_exit "Certificate generation failed."
fi

if [ ! -f "$PROJECT_ROOT/certs/truststore.jks" ] || [ ! -s "$PROJECT_ROOT/certs/truststore.jks" ]; then
    log_info "Java truststore not found or empty. Generating truststore.jks..."
    rm -f "$PROJECT_ROOT/certs/truststore.jks"
    keytool -importcert -noprompt \
      -keystore "$PROJECT_ROOT/certs/truststore.jks" \
      -storepass "$TRUSTSTORE_PASSWORD" \
      -alias keycloak \
      -file "$PROJECT_ROOT/certs/keycloak-cert.pem" || error_exit "Java truststore generation failed."
fi

chmod -R 777 "$PROJECT_ROOT/certs" 2>/dev/null || true



log_info "Building and starting infrastructure containers..."
docker compose -f "$PROJECT_ROOT/scripts/docker-compose.yml" up -d keycloak-db keycloak || error_exit "Docker Compose infrastructure start failed."

# Wait for keycloak database and keycloak server
wait_for_service_healthy "$PROJECT_ROOT/scripts/docker-compose.yml" "keycloak-db"
wait_for_service_healthy "$PROJECT_ROOT/scripts/docker-compose.yml" "keycloak"

log_info "Starting keycloak-setup (Keycloak configuration automation)..."
docker compose -f "$PROJECT_ROOT/scripts/docker-compose.yml" up -d keycloak-setup || error_exit "Keycloak setup failed."

# Wait for keycloak setup to complete successfully
wait_for_service_exit_zero "$PROJECT_ROOT/scripts/docker-compose.yml" "keycloak-setup"

log_info "Building and starting database and object storage..."
docker compose -f "$PROJECT_ROOT/docker-compose.yml" up -d postgres minio || error_exit "Docker Compose root services start failed."

# Wait for postgres and minio
wait_for_service_healthy "$PROJECT_ROOT/docker-compose.yml" "postgres"
wait_for_service_healthy "$PROJECT_ROOT/docker-compose.yml" "minio"

log_info "Building and starting application (forcing E2E Keycloak issuer port 8444)..."
export KC_ISSUER_URI="https://localhost:8444/realms/mail-and-media-shop-realm"
docker compose -f "$PROJECT_ROOT/docker-compose.yml" up -d --build app || error_exit "Docker Compose app start failed."

# Wait for application to be healthy
wait_for_service_healthy "$PROJECT_ROOT/docker-compose.yml" "app"

# ==============================================================================
# PHASE 3: Network Integration (for CI/CD environments)
# ==============================================================================

log_info "Connecting CI container to Docker Compose network..."
# Determine network name (preserving hyphens)
COMPOSE_PROJECT_NAME=$(basename "$PROJECT_ROOT" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9_-]//g')
NETWORK_NAME="${COMPOSE_PROJECT_NAME}_default"

# Get current container ID more reliably
if [ -f /proc/self/cgroup ]; then
    CURRENT_CONTAINER=$(cat /proc/self/cgroup | grep "docker" | head -n 1 | cut -d '/' -f 3 2>/dev/null)
fi

if [ -z "$CURRENT_CONTAINER" ]; then
    CURRENT_CONTAINER=$(hostname)
fi

log_info "Determined current container/host ID: $CURRENT_CONTAINER"

# Check if we are actually inside a container
if docker inspect "$CURRENT_CONTAINER" &>/dev/null; then
    log_info "Attempting to connect container $CURRENT_CONTAINER to network $NETWORK_NAME"
    docker network connect "$NETWORK_NAME" "$CURRENT_CONTAINER" 2>/dev/null || log_info "Already connected or connection failed."
    
    # Verify connection
    if docker inspect "$CURRENT_CONTAINER" -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}' | grep -q "$NETWORK_NAME"; then
        log_info "✅ Successfully connected to $NETWORK_NAME"
    else
        log_error "❌ Failed to connect to $NETWORK_NAME. Communication might fail."
    fi
else
    log_info "Running on host or could not find container ID in Docker. Skipping network connect."
fi

# Debug: Show networks for this container
log_info "Current container networks:"
docker inspect "$CURRENT_CONTAINER" -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}' 2>/dev/null || echo "Unknown"

# ==============================================================================
# PHASE 5: Reporting & Log Capture
# ==============================================================================

# Capture container logs for analysis (essential for GitLab CI artifacts)
log_info "Capturing container logs for 'app'..."
mkdir -p "$PROJECT_ROOT/logs"
docker compose -f "$PROJECT_ROOT/docker-compose.yml" logs app > "$PROJECT_ROOT/logs/app_container.log"
log_info "App logs saved to logs/app_container.log"