#!/bin/bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$PROJECT_DIR"

TEMPORAL_WORKER_CONTROLLER_CHART_VERSION="${TEMPORAL_WORKER_CONTROLLER_CHART_VERSION:-0.29.1}"
TEMPORAL_WORKER_CONTROLLER_NAMESPACE="temporal-worker-controller-system"

echo "🔧 Setting up k3d infrastructure..."

if ! k3d cluster list temporal-oms 2>/dev/null | grep -q temporal-oms; then
    echo "→ Creating k3d cluster (temporal-oms)..."
    k3d cluster create temporal-oms --k3s-arg '--disable=traefik@server:0'
else
    echo "✓ k3d cluster (temporal-oms) already exists"
fi

# Always (re)write the kubeconfig — /tmp can be wiped between sessions.
k3d kubeconfig get temporal-oms > /tmp/k3d-config.yaml
export KUBECONFIG=/tmp/k3d-config.yaml

echo "→ Creating Kubernetes namespaces..."
kubectl create namespace temporal-oms-apps --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl create namespace temporal-oms-processing --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl create namespace temporal-oms-fulfillment --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl create namespace temporal-oms-enablements --dry-run=client -o yaml | kubectl apply -f - >/dev/null

echo "→ Installing cert-manager..."
if ! kubectl get crd certificates.cert-manager.io &>/dev/null; then
    kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.13.0/cert-manager.yaml >/dev/null
    until kubectl get deployment cert-manager -n cert-manager &>/dev/null; do sleep 2; done
    kubectl -n cert-manager wait --for=condition=available deployment/cert-manager --timeout=120s
    kubectl -n cert-manager wait --for=condition=available deployment/cert-manager-webhook --timeout=120s
    kubectl -n cert-manager wait --for=condition=available deployment/cert-manager-cainjector --timeout=120s
else
    echo "✓ cert-manager already installed"
fi

echo "→ Installing Temporal Worker Controller CRDs..."
helm template temporal-worker-controller-crds \
    oci://docker.io/temporalio/temporal-worker-controller-crds \
    --version "$TEMPORAL_WORKER_CONTROLLER_CHART_VERSION" \
    --namespace "$TEMPORAL_WORKER_CONTROLLER_NAMESPACE" | kubectl apply -f - >/dev/null
kubectl wait --for=condition=established crd/connections.temporal.io --timeout=60s
kubectl wait --for=condition=established crd/workerdeployments.temporal.io --timeout=60s
kubectl wait --for=condition=established crd/workerresourcetemplates.temporal.io --timeout=60s

echo "→ Installing Temporal Worker Controller..."
helm upgrade --install temporal-worker-controller \
    oci://docker.io/temporalio/temporal-worker-controller \
    --version "$TEMPORAL_WORKER_CONTROLLER_CHART_VERSION" \
    --namespace "$TEMPORAL_WORKER_CONTROLLER_NAMESPACE" \
    --create-namespace >/dev/null

echo "→ Installing Traefik Ingress..."
kubectl apply -f "$PROJECT_DIR/k8s/ingress/traefik-deployment.yaml" >/dev/null
kubectl wait --for=condition=ready pod -l app=traefik -n traefik --timeout=60s 2>/dev/null || true

if [ "${OVERLAY:-local}" = "cloud" ]; then
    echo "→ Applying cloud secrets from config/*.secret.yaml..."

    AUTOMATIONS_KEY=$(yq '.temporal.connection.api-key' "$PROJECT_DIR/config/acme.automations.secret.yaml")
    PROCESSING_KEY=$(yq '.temporal.connection.api-key' "$PROJECT_DIR/config/acme.processing.secret.yaml")
    APPS_KEY=$(yq '.temporal.connection.api-key' "$PROJECT_DIR/config/acme.apps.secret.yaml")
    FULFILLMENT_KEY=$(yq '.temporal.connection.api-key' "$PROJECT_DIR/config/acme.fulfillment.secret.yaml")
    if [ -f "$PROJECT_DIR/config/acme.enablements.secret.yaml" ]; then
        ENABLEMENTS_KEY=$(yq '.temporal.connection.api-key' "$PROJECT_DIR/config/acme.enablements.secret.yaml")
    else
        ENABLEMENTS_KEY="$AUTOMATIONS_KEY"
    fi

    kubectl create secret generic temporal-processing-api-key \
        -n temporal-oms-processing \
        --from-literal=TEMPORAL_API_KEY="$AUTOMATIONS_KEY" \
        --from-literal="temporal-secret.yaml=spring.temporal.connection.api-key: \"$PROCESSING_KEY\"" \
        --dry-run=client -o yaml | kubectl apply -f - >/dev/null

    kubectl create secret generic temporal-apps-api-key \
        -n temporal-oms-apps \
        --from-literal="temporal-secret.yaml=spring.temporal.connection.api-key: \"$APPS_KEY\"" \
        --dry-run=client -o yaml | kubectl apply -f - >/dev/null

    kubectl create secret generic temporal-fulfillment-api-key \
        -n temporal-oms-fulfillment \
        --from-literal="temporal-secret.yaml=spring.temporal.connection.api-key: \"$FULFILLMENT_KEY\"" \
        --dry-run=client -o yaml | kubectl apply -f - >/dev/null

    kubectl create secret generic temporal-enablements-api-key \
        -n temporal-oms-enablements \
        --from-literal="temporal-secret.yaml=spring.temporal.connection.api-key: \"$ENABLEMENTS_KEY\"" \
        --dry-run=client -o yaml | kubectl apply -f - >/dev/null
fi

echo "✅ k3d infrastructure ready!"
echo ""
echo "Next step: ./scripts/k3d/app-deploy.sh"
