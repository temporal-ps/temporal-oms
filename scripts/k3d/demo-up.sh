#!/bin/bash
set -e

OVERLAY="${OVERLAY:-local}"

echo "🚀 Starting complete k3d demo environment (${OVERLAY} Temporal)..."
echo ""

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$PROJECT_DIR"

bash scripts/k3d/infra-up.sh
echo ""
OVERLAY="${OVERLAY}" bash scripts/k3d/app-deploy.sh
echo ""
echo "🎉 k3d demo environment ready!"
echo ""
echo "API Access:"
echo "  ./scripts/k3d/tunnel.sh  (in another terminal)"
echo "  Then: curl http://localhost:8080/api/actuator/health"
echo "        curl http://localhost:8050/actuator/health"
echo "        open http://localhost:3000  (Web UI: Commerce + Payments demo)"
echo ""
echo "Commands:"
echo "  ./scripts/setup-temporal-namespaces.sh - Create local Temporal namespaces/endpoints"
echo "  ./scripts/k3d/demo-down.sh     - Tear down everything"
echo "  ./scripts/k3d/app-deploy.sh    - Redeploy apps only"
echo "  ./scripts/k3d/status.sh        - Check deployment status"
echo "  ./scripts/k3d/tunnel.sh        - Port-forward (run in another terminal)"
echo ""
echo "Use OVERLAY to switch Temporal backend:"
echo "  OVERLAY=cloud ./scripts/k3d/demo-up.sh   - Deploy with Temporal Cloud"
echo "  OVERLAY=local ./scripts/k3d/demo-up.sh   - Deploy with localhost Temporal (default)"
