#!/bin/bash
set -e

OVERLAY="${OVERLAY:-local}"

echo "🚀 Starting complete k3d demo environment (${OVERLAY} Temporal)..."
echo ""

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$PROJECT_DIR"

if [ "$OVERLAY" = "local" ]; then
    # shellcheck source=../_lib/demo-local.sh
    source scripts/_lib/demo-local.sh
    demo_require_no_local_stack
    demo_ensure_host_temporal
    demo_setup_namespaces
fi

# Resume path: reuse a cluster left by a soft demo-down.sh when its apps match OVERLAY.
# REDEPLOY=1 forces the full infra-up.sh and app-deploy.sh path (rebuilds images from current code).
resumed=false
if [ "${REDEPLOY:-0}" != "1" ] && k3d cluster list temporal-oms 2>/dev/null | grep -q temporal-oms; then
    echo "→ Starting existing k3d cluster (temporal-oms)..."
    k3d cluster start temporal-oms --wait >/dev/null
    k3d kubeconfig get temporal-oms > /tmp/k3d-config.yaml
    export KUBECONFIG=/tmp/k3d-config.yaml

    deployed_overlay="$(kubectl get namespace temporal-oms-apps \
        -o jsonpath='{.metadata.labels.temporal-oms/overlay}' 2>/dev/null || true)"
    if [ "$deployed_overlay" = "$OVERLAY" ]; then
        echo "→ Reusing deployed apps (overlay=${OVERLAY}); waiting for them to become available..."
        for ns in traefik temporal-oms-apps temporal-oms-processing temporal-oms-enablements temporal-oms-fulfillment temporal-oms-web; do
            kubectl wait --for=condition=available deployment --all -n "$ns" --timeout=180s >/dev/null
        done
        echo "  Images were NOT rebuilt. Use REDEPLOY=1 to deploy current code."
        resumed=true
    else
        echo "→ Deployed overlay '${deployed_overlay:-none}' does not match '${OVERLAY}'; running full deploy"
    fi
fi

if [ "$resumed" != true ]; then
    bash scripts/k3d/infra-up.sh
    echo ""
    OVERLAY="${OVERLAY}" bash scripts/k3d/app-deploy.sh
fi
echo ""
if [ "$OVERLAY" = "local" ]; then
    demo_start_tunnel scripts/k3d/tunnel.sh
    echo ""
    echo "🎉 k3d demo environment ready!"
    echo ""
    echo "  Web UI:       http://localhost:3000   <- start here"
    echo "  Admin:        http://localhost:3000/admin"
    echo "  Temporal UI:  http://localhost:8233"
    echo ""
    echo "  Worker Deployments start with no current version. Before placing orders or"
    echo "  starting load, open Admin and roll out OMS v1 (or any later version)."
    echo ""
    echo "  Stop: ./scripts/k3d/demo-down.sh  (HARD=1 deletes the cluster)"
else
    echo "🎉 k3d demo environment ready!"
    echo ""
    echo "API Access:"
    echo "  ./scripts/k3d/tunnel.sh  (in another terminal)"
    echo "  Then: open http://localhost:3000"
fi
echo ""
echo "Commands:"
echo "  ./scripts/k3d/demo-down.sh     - Stop the cluster (HARD=1 deletes it)"
echo "  ./scripts/k3d/app-deploy.sh    - Redeploy apps only"
echo "  ./scripts/k3d/status.sh        - Check deployment status"
echo ""
echo "Use OVERLAY to switch Temporal backend:"
echo "  OVERLAY=cloud ./scripts/k3d/demo-up.sh   - Deploy with Temporal Cloud"
echo "  OVERLAY=local ./scripts/k3d/demo-up.sh   - Deploy with localhost Temporal (default)"
