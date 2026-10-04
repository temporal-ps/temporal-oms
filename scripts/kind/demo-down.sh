#!/bin/bash
set -e

# Default: stop the KinD node container and keep its images, charts, and app deployments for a fast demo-up.sh.
# HARD=1: remove the apps and delete the cluster.
HARD="${HARD:-0}"

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$PROJECT_DIR"

if [ "$HARD" != "1" ]; then
    echo "⏸️  Stopping KinD demo environment (soft down)..."
    docker stop temporal-oms-control-plane >/dev/null 2>&1 || true
    echo "✅ KinD cluster stopped. ./scripts/kind/demo-up.sh resumes it."
    echo "   Full teardown: HARD=1 ./scripts/kind/demo-down.sh"
    exit 0
fi

echo "🛑 Tearing down KinD demo environment..."
echo ""

bash scripts/kind/app-down.sh
echo ""
bash scripts/kind/infra-down.sh
echo ""
echo "✅ KinD demo environment removed"
