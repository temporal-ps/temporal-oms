#!/bin/bash

echo "🔌 Tearing down KinD infrastructure..."

export KUBECONFIG=/tmp/kind-config.yaml

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

echo "→ Deleting KinD cluster..."
kind delete cluster --name temporal-oms 2>/dev/null || true

echo "✅ KinD infrastructure stopped"
