#!/bin/bash

echo "🔌 Tearing down k3d infrastructure..."

export KUBECONFIG=/tmp/k3d-config.yaml

echo "→ Stopping applications..."
kubectl delete namespace \
  temporal-oms-apps \
  temporal-oms-processing \
  temporal-oms-fulfillment \
  temporal-oms-enablements \
  temporal-oms-web \
  --ignore-not-found \
  --wait=false \
  2>/dev/null || true

echo "→ Stopping Traefik..."
kubectl delete namespace traefik --ignore-not-found --wait=false 2>/dev/null || true

echo "→ Deleting k3d cluster..."
k3d cluster delete temporal-oms 2>/dev/null || true

echo "✅ k3d infrastructure stopped"
