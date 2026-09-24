#!/bin/bash
set -e

export KUBECONFIG=/tmp/kind-config.yaml

echo "🗑️  Removing KinD applications..."

kubectl delete namespace \
  temporal-oms-apps \
  temporal-oms-processing \
  temporal-oms-fulfillment \
  temporal-oms-enablements \
  temporal-oms-web \
  --ignore-not-found \
  --wait=false \
  2>/dev/null || true

echo "✅ KinD applications removed"
