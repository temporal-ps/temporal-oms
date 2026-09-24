#!/bin/bash
set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$PROJECT_DIR"
. "$PROJECT_DIR/scripts/_lib/java-tools.sh"
. "$PROJECT_DIR/scripts/_lib/k8s-runtime-secrets.sh"

export KUBECONFIG=/tmp/k3d-config.yaml

OVERLAY="${OVERLAY:-local}"
PROCESSING_WORKER_MODE="${PROCESSING_WORKER_MODE:-unversioned}"
KUSTOMIZE_OVERLAY="$OVERLAY"
if [ "$OVERLAY" = "local" ]; then
  KUSTOMIZE_OVERLAY="k3d-local"
fi

echo "📦 Building and deploying applications to k3d (${OVERLAY} Temporal)..."

echo "→ Building Java projects..."
JAVAC_EXECUTABLE="$(resolve_javac_executable)"
(cd java && mvn -Djavac.executable="$JAVAC_EXECUTABLE" clean install -DskipTests -q)

echo "→ Building Docker images..."
docker build -q -t temporal-oms/apps-api:latest \
  -f java/apps/apps-api/docker/Dockerfile java/apps/apps-api

docker build -q -t temporal-oms/apps-worker:latest \
  -f java/apps/apps-workers/docker/Dockerfile java/apps/apps-workers

docker build -q -t temporal-oms/processing-api:latest \
  -f java/processing/processing-api/docker/Dockerfile java/processing/processing-api

docker build -q -t temporal-oms/processing-workers:latest \
  -t temporal-oms/processing-workers:v1 \
  -t temporal-oms/processing-workers:v2 \
  -t temporal-oms/processing-workers:v3 \
  -t temporal-oms/processing-workers:v4 \
  -f java/processing/processing-workers/docker/Dockerfile java/processing/processing-workers

docker build -q -t temporal-oms/enablements-api:latest \
  -f java/enablements/enablements-api/docker/Dockerfile java/enablements/enablements-api

docker build -q -t temporal-oms/enablements-workers:latest \
  -f java/enablements/enablements-workers/docker/Dockerfile java/enablements/enablements-workers

docker build -q -t temporal-oms/fulfillment-workers:latest \
  -f java/fulfillment/fulfillment-workers/docker/Dockerfile java/fulfillment/fulfillment-workers

docker build -q -t temporal-oms/fulfillment-python-worker:latest \
  -f python/fulfillment/Dockerfile python

docker build -q -t temporal-oms/web:latest \
  -f web/Dockerfile web

echo "→ Importing images into k3d..."
k3d image import \
  temporal-oms/apps-api:latest \
  temporal-oms/apps-worker:latest \
  temporal-oms/processing-api:latest \
  temporal-oms/processing-workers:latest \
  temporal-oms/processing-workers:v1 \
  temporal-oms/processing-workers:v2 \
  temporal-oms/processing-workers:v3 \
  temporal-oms/processing-workers:v4 \
  temporal-oms/enablements-api:latest \
  temporal-oms/enablements-workers:latest \
  temporal-oms/fulfillment-workers:latest \
  temporal-oms/fulfillment-python-worker:latest \
  temporal-oms/web:latest \
  --cluster temporal-oms

echo "→ Deploying to k3d..."
echo "  using kustomize overlay: ${KUSTOMIZE_OVERLAY}"
kubectl apply -k "k8s/overlays/${KUSTOMIZE_OVERLAY}" >/dev/null
# The WorkerDeployment CRD may already exist and be mid-promotion (the Admin UI's
# deployWorkerVersion activity patches it directly) - never assume a fresh, non-CRD
# bring-up. Only provision/scale it on its first creation; on every later redeploy,
# leave its spec exactly as the last promotion left it and just decide, from its
# observed replica count, whether the plain baseline Deployment needs to stay deleted.
crd_replicas=""
if kubectl get workerdeployment processing-workers -n temporal-oms-processing >/dev/null 2>&1; then
  crd_replicas="$(kubectl get workerdeployment processing-workers -n temporal-oms-processing -o jsonpath='{.spec.replicas}')"
fi

crd_is_active=false
if [ "$PROCESSING_WORKER_MODE" = "versioned" ]; then
  crd_is_active=true
elif [ -n "$crd_replicas" ] && [ "$crd_replicas" -gt 0 ]; then
  crd_is_active=true
fi

if [ -z "$crd_replicas" ]; then
  echo "  provisioning processing-workers WorkerDeployment CRD"
  kubectl apply -k "k8s/processing-versioned/overlays/${KUSTOMIZE_OVERLAY}" >/dev/null
  if [ "$crd_is_active" != true ]; then
    kubectl patch workerdeployment processing-workers -n temporal-oms-processing \
      --type=merge -p '{"spec":{"replicas":0}}' >/dev/null 2>&1 || true
  fi
else
  echo "  processing-workers WorkerDeployment CRD already exists (replicas=${crd_replicas}); leaving its spec as-is"
fi

if [ "$crd_is_active" = true ]; then
  echo "  using WorkerDeployment for processing-workers"
  kubectl delete deployment processing-workers -n temporal-oms-processing --ignore-not-found >/dev/null
else
  echo "  processing-workers stays on the unversioned baseline Deployment"
fi
kubectl apply -f k8s/ingress/apps-api-ingress.yaml >/dev/null
kubectl apply -f k8s/ingress/processing-api-ingress.yaml >/dev/null
kubectl apply -f k8s/ingress/enablements-api-ingress.yaml >/dev/null
kubectl apply -f k8s/ingress/web-ingress.yaml >/dev/null
apply_runtime_api_key_secrets "$PROJECT_DIR"

echo "→ Restarting pods..."
for ns in temporal-oms-apps temporal-oms-processing temporal-oms-enablements temporal-oms-fulfillment temporal-oms-web; do
  kubectl delete pods -n "$ns" --all 2>/dev/null || true
done

sleep 8

if [ "$PROCESSING_WORKER_MODE" = "versioned" ]; then
  echo "→ Waiting for processing WorkerDeployment..."
  if ! kubectl wait \
    --for=condition=Ready \
    workerdeployment/processing-workers \
    -n temporal-oms-processing \
    --timeout=180s; then
    echo "ERROR: processing WorkerDeployment did not become Ready." >&2
    kubectl describe workerdeployment processing-workers -n temporal-oms-processing >&2 || true
    exit 1
  fi
fi

echo "✅ Applications deployed to k3d!"
echo ""
scripts/k3d/status.sh
