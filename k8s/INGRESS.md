# Apps API Ingress Setup

The apps-api service is exposed via Traefik ingress controller running in your KinD or k3d cluster,
making it accessible from your local machine.

## Quick Start

```bash
# Full setup with KinD cluster and ingress
./scripts/kind/demo-up.sh

# Or full setup with k3d cluster and ingress
./scripts/k3d/demo-up.sh

# In a new terminal, port-forward Traefik to localhost:8080
./scripts/kind/tunnel.sh
# or
./scripts/k3d/tunnel.sh

# Now you can access the API
curl http://localhost:8080/api/actuator/health
```

## Access Methods

### Method 1: Port-Forward (Recommended for local demo)

```bash
export KUBECONFIG=/tmp/kind-config.yaml
# or
export KUBECONFIG=/tmp/k3d-config.yaml

./scripts/kind/tunnel.sh
# or
./scripts/k3d/tunnel.sh
# API available at: http://localhost:8080/api
```

This is the simplest method for local development. Traefik's ingress controller will be port-forwarded to your `localhost:8080`.

### Method 2: Using api.local Hostname

Add to your `/etc/hosts`:
```bash
echo "127.0.0.1 api.local" >> /etc/hosts
```

Then with port-forwarding running:
```bash
curl http://api.local/api/actuator/health
```

## API Endpoints

Apps API runs on port 8080 (HTTP) and 9090 (metrics) within the cluster. Via Traefik ingress:

- **Main API**: `http://localhost:8080/api/*`
- **Health check**: `http://localhost:8080/api/actuator/health`
- **Metrics**: `http://localhost:8080/api/actuator/prometheus`
- **OpenAPI**: `http://localhost:8080/api/v3/api-docs`
- **Swagger UI**: `http://localhost:8080/api/docs`

## Routing Configuration

The Traefik ingress is configured with a path-based rule:

- **Rule**: Any request to `/api*` is routed to the apps-api service
- **Service**: `apps-api` in `temporal-oms-apps` namespace
- **Port**: 8080

Example requests:
```bash
curl http://localhost:8080/api/actuator/health
curl http://localhost:8080/api/v1/commerce-app/clothing
curl http://localhost:8080/api/actuator/prometheus
```

## Testing the API

```bash
# Health check
curl http://localhost:8080/api/actuator/health

# List available endpoints
curl http://localhost:8080/api/actuator

# Get metrics
curl http://localhost:8080/api/actuator/prometheus

# OpenAPI spec
curl http://localhost:8080/api/v3/api-docs | jq

# Sample domain endpoint
curl http://localhost:8080/api/v1/commerce-app/clothing
```

## Troubleshooting

**Cannot reach API (connection refused):**
```bash
# Make sure tunnel.sh is running in another terminal
./scripts/kind/tunnel.sh
# or
./scripts/k3d/tunnel.sh

# Check Traefik pods
export KUBECONFIG=/tmp/kind-config.yaml
# or
export KUBECONFIG=/tmp/k3d-config.yaml
kubectl get pods -n traefik

# Check Ingress is created
kubectl get ingress -n temporal-oms-apps
```

**Cannot reach API (504 Bad Gateway):**
```bash
# Check that apps-api pods are running
kubectl get pods -n temporal-oms-apps -l app=apps-api

# Check apps-api service endpoints
kubectl get endpoints -n temporal-oms-apps apps-api

# Check Ingress routing
kubectl describe ingress apps-api -n temporal-oms-apps
```

**View Traefik logs for debugging:**
```bash
export KUBECONFIG=/tmp/kind-config.yaml
# or
export KUBECONFIG=/tmp/k3d-config.yaml
kubectl logs -n traefik -l app.kubernetes.io/name=traefik -f
```

**View apps-api logs:**
```bash
export KUBECONFIG=/tmp/kind-config.yaml
# or
export KUBECONFIG=/tmp/k3d-config.yaml
kubectl logs -n temporal-oms-apps -l app=apps-api --tail=50
```

## Cleanup

Ingress is torn down with the rest of the demo:
```bash
HARD=1 ./scripts/kind/demo-down.sh
# or
HARD=1 ./scripts/k3d/demo-down.sh
```

Without `HARD=1`, either script only stops the cluster and keeps Ingress in place.

This removes the entire selected cluster including all services, ingress, and pods.
