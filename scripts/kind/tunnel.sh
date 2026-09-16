#!/bin/bash

export KUBECONFIG=/tmp/kind-config.yaml

echo "🌉 Setting up KinD port-forwards..."
echo "  Apps API at:        http://localhost:8080  (host: localhost or api.local)"
echo "  Processing API at:  http://localhost:8070  (host: localhost or processing-api.local)"
echo "  Enablements API at: http://localhost:8050"
echo "  Web UI at:          http://localhost:3000"
echo ""
echo "Press Ctrl+C to stop"
echo ""

kubectl port-forward -n traefik svc/traefik 8080:80 8070:8070 3000:3000 &
kubectl port-forward -n temporal-oms-enablements svc/enablements-api 8050:8050 &

wait
