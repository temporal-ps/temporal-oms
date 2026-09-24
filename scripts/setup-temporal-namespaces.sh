#!/bin/bash

# Setup Temporal namespaces and Nexus endpoints for local development
# Creates local namespaces and cross-namespace Nexus endpoints.

set -e

# Use 127.0.0.1 instead of localhost by default. On macOS, localhost may
# resolve through IPv6 first while start-dev listens on IPv4, which can make
# Temporal CLI setup calls hang even though the dev server is healthy.
TEMPORAL_ADDRESS="${TEMPORAL_ADDRESS:-127.0.0.1:7233}"
TEMPORAL_APPS_NAMESPACE="${TEMPORAL_APPS_NAMESPACE:-apps}"
TEMPORAL_PROCESSING_NAMESPACE="${TEMPORAL_PROCESSING_NAMESPACE:-processing}"
TEMPORAL_FULFILLMENT_NAMESPACE="${TEMPORAL_FULFILLMENT_NAMESPACE:-fulfillment}"
TEMPORAL_ENABLEMENTS_NAMESPACE="${TEMPORAL_ENABLEMENTS_NAMESPACE:-default}"
TEMPORAL_CLI=(temporal --disable-config-file --disable-config-env --address "$TEMPORAL_ADDRESS")

# Once any build-id is set current for a Worker Deployment name, there is no Temporal API
# to unset it back to "no current version" - only to change it to a different registered
# one (confirmed: `worker deployment delete-version` refuses while it's current; `delete`
# refuses while any version exists). So registering build-id=local as current here is a
# one-way door: it rules out ever demonstrating OMS v1 (spec.md's true, no-Worker-
# Versioning-at-all baseline) against this apps/processing/fulfillment namespace again.
# SKIP_VERSION_REGISTRATION=1 leaves them with zero version history from the start, so
# the OMS-rollout Admin UI's first promotion - even to v1 - is the one engaging Worker
# Versioning for the first time, cleanly, instead of colliding with this script's own.
SKIP_VERSION_REGISTRATION="${SKIP_VERSION_REGISTRATION:-0}"

create_namespace() {
  local namespace="$1"
  echo "Creating namespace: $namespace"
  "${TEMPORAL_CLI[@]}" operator namespace create \
    --namespace "$namespace" \
    --command-timeout 30s 2>/dev/null || echo "  ($namespace namespace may already exist)"
}

upsert_nexus_endpoint() {
  local name="$1"
  local target_namespace="$2"
  local target_task_queue="$3"
  local description="$4"

  echo "$description"
  if "${TEMPORAL_CLI[@]}" operator nexus endpoint get --name "$name" --command-timeout 30s &>/dev/null; then
    "${TEMPORAL_CLI[@]}" operator nexus endpoint update \
      --name "$name" \
      --target-namespace "$target_namespace" \
      --target-task-queue "$target_task_queue" \
      --command-timeout 30s 2>/dev/null || echo "  (endpoint update skipped)"
  else
    "${TEMPORAL_CLI[@]}" operator nexus endpoint create \
      --name "$name" \
      --target-namespace "$target_namespace" \
      --target-task-queue "$target_task_queue" \
      --command-timeout 30s 2>/dev/null || echo "  (endpoint create skipped)"
  fi
}

echo "Setting up Temporal namespaces and Nexus endpoints..."
echo "Temporal Address: $TEMPORAL_ADDRESS"
echo ""

# Wait for Temporal server to be ready
# Stage 1: frontend gRPC accepting connections
echo "Waiting for Temporal frontend at $TEMPORAL_ADDRESS..."
until "${TEMPORAL_CLI[@]}" operator cluster health --command-timeout 2s &>/dev/null; do
  echo "  frontend not ready, retrying in 2s..."
  sleep 2
done
echo "  frontend ready"

# Stage 2: namespace cache ready. Avoid workflow list here: on start-dev it can time out while
# visibility warms up even though namespace/operator APIs are ready for setup.
echo "Waiting for default namespace registration..."
until "${TEMPORAL_CLI[@]}" operator namespace describe --namespace default --command-timeout 2s &>/dev/null; do
  echo "  internal services not ready, retrying in 2s..."
  sleep 2
done
echo "  default namespace ready"
echo ""

create_namespace "$TEMPORAL_APPS_NAMESPACE"
create_namespace "$TEMPORAL_PROCESSING_NAMESPACE"
create_namespace "$TEMPORAL_FULFILLMENT_NAMESPACE"
create_namespace "$TEMPORAL_ENABLEMENTS_NAMESPACE"

if [ "$SKIP_VERSION_REGISTRATION" = "1" ]; then
  echo ""
  echo "SKIP_VERSION_REGISTRATION=1: leaving apps/processing/fulfillment Worker Deployments"
  echo "with no current version (OMS v1's baseline - no Worker Versioning at all). New"
  echo "workflow starts on these task queues route via classic, non-versioned dispatch"
  echo "until something is promoted through the Admin UI (or set-current-version) later."
  echo ""
  echo "Note: this only covers apps/fulfillment. processing's Worker Versioning is engaged"
  echo "independently by the Temporal Worker Controller once its WorkerDeployment CRD comes"
  echo "up (scripts/kind|k3d/app-deploy.sh's default PROCESSING_WORKER_MODE=versioned) - this"
  echo "flag does not affect that."
else
  echo ""
  echo "Setting 'apps' Worker Deployment to build-id='local'"
  echo ""
  "${TEMPORAL_CLI[@]}" worker deployment set-current-version \
    --deployment-name apps \
    --build-id local \
    --allow-no-pollers \
    --namespace "$TEMPORAL_APPS_NAMESPACE" \
    --command-timeout 30s \
    --yes 2>/dev/null || echo "  (skipped)"

  echo ""
  echo "Setting 'processing' Worker Deployment to build-id='local'"
  echo ""
  "${TEMPORAL_CLI[@]}" worker deployment set-current-version \
    --deployment-name processing \
    --build-id local \
    --allow-no-pollers \
    --namespace "$TEMPORAL_PROCESSING_NAMESPACE" \
    --command-timeout 30s \
    --yes 2>/dev/null || echo "  (skipped)"

  echo ""
  echo "Setting 'fulfillment' Worker Deployment to build-id='local'"
  echo ""
  "${TEMPORAL_CLI[@]}" worker deployment set-current-version \
    --deployment-name fulfillment \
    --build-id local \
    --allow-no-pollers \
    --namespace "$TEMPORAL_FULFILLMENT_NAMESPACE" \
    --command-timeout 30s \
    --yes 2>/dev/null || echo "  (skipped)"
fi

echo ""
echo "Registering Nexus endpoints..."
echo ""

upsert_nexus_endpoint \
  "oms-processing-v1" \
  "$TEMPORAL_PROCESSING_NAMESPACE" \
  "processing" \
  "Registering endpoint 'oms-processing-v1' targeting $TEMPORAL_PROCESSING_NAMESPACE/processing task queue"

upsert_nexus_endpoint \
  "oms-apps-v1" \
  "$TEMPORAL_APPS_NAMESPACE" \
  "apps" \
  "Registering endpoint 'oms-apps-v1' targeting $TEMPORAL_APPS_NAMESPACE/apps task queue"

upsert_nexus_endpoint \
  "oms-integrations-v1" \
  "$TEMPORAL_ENABLEMENTS_NAMESPACE" \
  "integrations" \
  "Registering endpoint 'oms-integrations-v1' targeting $TEMPORAL_ENABLEMENTS_NAMESPACE/integrations task queue"

upsert_nexus_endpoint \
  "oms-fulfillment-v1" \
  "$TEMPORAL_FULFILLMENT_NAMESPACE" \
  "fulfillment" \
  "Registering endpoint 'oms-fulfillment-v1' targeting $TEMPORAL_FULFILLMENT_NAMESPACE/fulfillment task queue"

upsert_nexus_endpoint \
  "oms-fulfillment-agents-v1" \
  "$TEMPORAL_FULFILLMENT_NAMESPACE" \
  "agents" \
  "Registering endpoint 'oms-fulfillment-agents-v1' targeting $TEMPORAL_FULFILLMENT_NAMESPACE/agents task queue"

echo ""
echo "Registering custom search attributes..."
echo ""

# margin_leak: records shipping cost overage (cents) on fulfillment.Order workflows
"${TEMPORAL_CLI[@]}" operator search-attribute create \
  --namespace "$TEMPORAL_FULFILLMENT_NAMESPACE" \
  --name margin_leak \
  --type Int \
  --command-timeout 30s 2>/dev/null || echo "  (margin_leak attribute may already exist)"

# sla_breach_days: records SLA overage (days) on fulfillment.Order workflows
"${TEMPORAL_CLI[@]}" operator search-attribute create \
  --namespace "$TEMPORAL_FULFILLMENT_NAMESPACE" \
  --name sla_breach_days \
  --type Int \
  --command-timeout 30s 2>/dev/null || echo "  (sla_breach_days attribute may already exist)"

echo ""
echo "✓ Setup complete!"
echo ""
echo "To run workers with these namespaces:"
echo "  export TEMPORAL_APPS_NAMESPACE=$TEMPORAL_APPS_NAMESPACE                 # Java apps worker"
echo "  export TEMPORAL_PROCESSING_NAMESPACE=$TEMPORAL_PROCESSING_NAMESPACE     # Java processing worker"
echo "  export TEMPORAL_FULFILLMENT_NAMESPACE=$TEMPORAL_FULFILLMENT_NAMESPACE   # Fulfillment worker"
echo "  export TEMPORAL_ENABLEMENTS_NAMESPACE=$TEMPORAL_ENABLEMENTS_NAMESPACE   # Java enablements worker"
echo "  export TEMPORAL_ADDRESS=127.0.0.1:7233"
