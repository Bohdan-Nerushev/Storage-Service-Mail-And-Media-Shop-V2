#!/bin/bash
set -eo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/utils.sh"
source "$(dirname "${BASH_SOURCE[0]}")/env_loader.sh"
export IMAGE_TAG="${IMAGE_TAG:-$(git rev-parse --short HEAD 2>/dev/null || echo "latest")}"
$SSH_CMD "mkdir -p ~/mam-deployments"
$SCP_CMD
$SSH_CMD "export KUBECONFIG=/etc/rancher/k3s/k3s.yaml && cd ~/mam-deployments/mail-and-media-shop && helm dependency build && helm upgrade --install mam-dev . -f values-dev.yaml --set app.image.tag=${IMAGE_TAG} --namespace mam-dev --wait --timeout 300s"
