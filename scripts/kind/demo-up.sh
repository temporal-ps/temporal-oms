#!/bin/bash
set -e

OVERLAY="${OVERLAY:-local}"

echo "🚀 Starting complete KinD demo environment (${OVERLAY} Temporal)..."
echo ""

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$PROJECT_DIR"

# Resume path: reuse a cluster left by a soft demo-down.sh when its apps match OVERLAY.
# REDEPLOY=1 forces the full infra-up.sh and app-deploy.sh path (rebuilds images from current code).
resumed=false
if [ "${REDEPLOY:-0}" != "1" ] && kind get clusters 2>/dev/null | grep -q temporal-oms; then
    echo "→ Starting existing KinD cluster (temporal-oms)..."
    docker start temporal-oms-control-plane >/dev/null
    kind get kubeconfig --name temporal-oms > /tmp/kind-config.yaml
    export KUBECONFIG=/tmp/kind-config.yaml
    for _ in $(seq 60); do kubectl get --raw /readyz >/dev/null 2>&1 && break; sleep 2; done
    kubectl wait --for=condition=Ready node --all --timeout=120s >/dev/null

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
    bash scripts/kind/infra-up.sh
    echo ""
    OVERLAY="${OVERLAY}" bash scripts/kind/app-deploy.sh
fi
echo ""
echo "🎉 KinD demo environment ready!"
echo ""
echo "API Access:"
echo "  ./scripts/kind/tunnel.sh  (in another terminal)"
echo "  Then: curl http://localhost:8080/api/actuator/health"
echo "        curl http://localhost:8050/actuator/health"
echo "        open http://localhost:3000  (Web UI: Commerce + Payments demo)"
echo ""
echo "Commands:"
echo "  ./scripts/setup-temporal-namespaces.sh - Create local Temporal namespaces/endpoints"
echo "  ./scripts/kind/demo-down.sh     - Stop the cluster (HARD=1 deletes it)"
echo "  ./scripts/kind/app-deploy.sh    - Redeploy apps only"
echo "  ./scripts/kind/status.sh        - Check deployment status"
echo "  ./scripts/kind/tunnel.sh        - Port-forward (run in another terminal)"
echo ""
echo "Use OVERLAY to switch Temporal backend:"
echo "  OVERLAY=cloud ./scripts/kind/demo-up.sh   - Deploy with Temporal Cloud"
echo "  OVERLAY=local ./scripts/kind/demo-up.sh   - Deploy with localhost Temporal (default)"
