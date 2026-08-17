#!/bin/bash

# ==============================================================================
# PHASE 1: Environment Initialization & Docker Verification
# ==============================================================================
set -o pipefail

# Resolve the absolute path to the project root directory.
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log_info() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') [INFO] $1"
}

log_error() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') [ERROR] $1" >&2
}

error_exit() {
    log_error "$1"
    # Ensure containers are down even on failure to free resources
    docker compose down -v 2>/dev/null
    exit 1
}

log_info "Starting Containerized E2E Runner"
log_info "Project Root: $PROJECT_ROOT"

# Load environment variables from .env
if [ -f "$PROJECT_ROOT/.env" ]; then
    log_info "Loading environment variables from .env"
    # Simple export for Docker Compose and script usage
    export $(grep -v '^#' "$PROJECT_ROOT/.env" | xargs)
fi

# Configurable Parameters
export APP_PORT=${APP_PORT:-8080}
export APP_HOST="${APP_HOST:-http://localhost}"
HEALTH_ENDPOINT="$APP_HOST:$APP_PORT/actuator/health"

# Verify Docker tools
for tool in docker curl; do
    if ! command -v "$tool" &> /dev/null; then
        error_exit "Required tool '$tool' is not installed."
    fi
done

# Check if 'docker compose' (plugin) works
if ! docker compose version &> /dev/null; then
    error_exit "Docker Compose plugin is not installed or not working."
fi

# ==============================================================================
# PHASE 2: Cleanup & Container Orchestration
# ==============================================================================

cleanup() {
    log_info "Cleanup triggered. Stopping and removing containers..."
    docker compose down -v
    if [[ "$VIRTUAL_ENV" != "" ]]; then
        log_info "Deactivating Python virtual environment..."
        deactivate 2>/dev/null || true
    fi
}

# Register cleanup to run on script exit
trap cleanup EXIT

log_info "Ensuring clean environment (stopping any existing containers)..."
docker compose down -v 2>/dev/null || true

log_info "Building and starting containers in detached mode..."
# Build without cache to ensure latest code changes are included
docker compose build --no-cache || error_exit "Docker build failed."
docker compose up -d || error_exit "Docker Compose up failed."

# ==============================================================================
# PHASE 3: Health Check
# ==============================================================================

log_info "Waiting for application to be healthy at $HEALTH_ENDPOINT..."
MAX_RETRIES=40
COUNT=0
READY=false

while [ $COUNT -lt "$MAX_RETRIES" ]; do
    if curl --output /dev/null --silent --head --fail "$HEALTH_ENDPOINT"; then
        READY=true
        break
    fi

    printf '.'
    sleep 3
    COUNT=$((COUNT+1))
    
    if (( COUNT % 10 == 0 )); then
        echo " ($COUNT/$MAX_RETRIES attempts completed)"
    fi
done

echo ""

if [ "$READY" = false ]; then
    log_error "Application failed to become healthy. Container logs for 'app':"
    docker compose logs app | tail -n 50
    error_exit "Readiness check timed out."
fi

log_info "Application is fully initialized and READY."



# ==============================================================================
# PHASE 4: Python Environment & Test Execution
# ==============================================================================

VENV_DIR="$PROJECT_ROOT/e2e_tests/venv"

# Use python3.12 or fallback to python3
PYTHON_EXEC=$(which python3.12 || which python3)

if [ -z "$PYTHON_EXEC" ]; then
    error_exit "Python 3 not found."
fi

if [ ! -d "$VENV_DIR" ] || [ ! -f "$VENV_DIR/bin/python3" ]; then
    log_info "Creating virtual environment..."
    rm -rf "$VENV_DIR"
    $PYTHON_EXEC -m venv "$VENV_DIR" || error_exit "Venv creation failed."
fi

log_info "Activating venv and installing requirements..."
source "$VENV_DIR/bin/activate" 2>/dev/null || true
"$VENV_DIR/bin/python3" -m ensurepip 2>/dev/null || true
"$VENV_DIR/bin/python3" -m pip install -r "$PROJECT_ROOT/e2e_tests/requirements.txt" --break-system-packages --quiet || pip install -r "$PROJECT_ROOT/e2e_tests/requirements.txt" --break-system-packages --quiet || error_exit "Pip install failed."

log_info "Executing Python E2E tests..."
export PYTHONPATH="$PROJECT_ROOT/e2e_tests"

"$VENV_DIR/bin/python3" "$PROJECT_ROOT/e2e_tests/main.py"
TEST_EXIT_CODE=$?

# ==============================================================================
# PHASE 5: Reporting & Log Capture
# ==============================================================================

# Capture container logs for analysis (essential for GitLab CI artifacts)
log_info "Capturing container logs for 'app'..."
mkdir -p "$PROJECT_ROOT/logs"
docker compose logs app > "$PROJECT_ROOT/logs/app_container.log"
log_info "App logs saved to logs/app_container.log"

if [ $TEST_EXIT_CODE -eq 0 ]; then
    log_info "RESULT: E2E Tests PASSED"
else
    log_error "RESULT: E2E Tests FAILED (Exit Code: $TEST_EXIT_CODE)"
fi

exit $TEST_EXIT_CODE