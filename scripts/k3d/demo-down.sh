#!/bin/bash
set -e

# Default: stop the cluster and keep its images, charts, and app deployments for a fast demo-up.sh.
# HARD=1: remove the apps and delete the cluster.
HARD="${HARD:-0}"

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$PROJECT_DIR"

if [ "$HARD" != "1" ]; then
    echo "⏸️  Stopping k3d demo environment (soft down)..."
    k3d cluster stop temporal-oms 2>/dev/null || true
    echo "✅ k3d cluster stopped. ./scripts/k3d/demo-up.sh resumes it."
    echo "   Full teardown: HARD=1 ./scripts/k3d/demo-down.sh"
    exit 0
fi

echo "🛑 Tearing down k3d demo environment..."
echo ""

bash scripts/k3d/app-down.sh
echo ""
bash scripts/k3d/infra-down.sh
echo ""
echo "✅ k3d demo environment removed"
