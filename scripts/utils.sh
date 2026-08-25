#!/bin/bash
# Common utility functions for E2E scripts

# Resolve the absolute path to the project root directory.
export PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log_info() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') [INFO] $1"
}

log_error() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') [ERROR] $1" >&2
}

error_exit() {
    log_error "$1"
    # Ensure containers are down even on failure to free resources if needed
    # docker compose down -v 2>/dev/null
    exit 1
}

check_dependencies() {
    for tool in "$@"; do
        if ! command -v "$tool" &> /dev/null; then
            error_exit "Required tool '$tool' is not installed."
        fi
    done
}
