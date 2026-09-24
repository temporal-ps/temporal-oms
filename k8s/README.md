# Kubernetes Deployment with Kustomize

This directory contains the Kubernetes manifests for the current OMS runtime topology. The demo
scripts can apply them to either KinD or k3d.

## Topology

Kubernetes namespaces:

- `temporal-oms-apps`: `apps-api`, `apps-worker`
- `temporal-oms-processing`: `processing-api`, `processing-workers`
- `temporal-oms-enablements`: `enablements-api`, `enablements-workers`
- `temporal-oms-fulfillment`: `fulfillment-workers`, `fulfillment-python-worker`
- `temporal-oms-web`: `web` (Svelte Commerce + Payments demo UI)

Temporal namespaces and task queues are configured by `scripts/setup-temporal-namespaces.sh`:

- `apps`: `apps`
- `processing`: `processing`, `commerce-app`, `support`
- `default`: `enablements`, `integrations`
- `fulfillment`: `fulfillment`, `fulfillment-carriers`, `agents`, `fulfillment-shipping`

The setup script also registers Nexus endpoints:

- `oms-processing-v1` -> `processing/processing`
- `oms-apps-v1` -> `apps/apps`
- `oms-integrations-v1` -> `default/integrations`
- `oms-fulfillment-v1` -> `fulfillment/fulfillment`
- `oms-fulfillment-agents-v1` -> `fulfillment/agents`

## Configuration

Each bounded context has two config surfaces:

- `temporal-oms-config`: environment variables consumed by containers, including `TEMPORAL_*_ADDRESS`, `TEMPORAL_*_NAMESPACE`, and `ENABLEMENTS_API_BASE_URL` where needed.
- `temporal-*-config`: mounted Spring config imported by Java apps in the `k8s` profile.

The in-cluster enablements API URL is:

```text
http://enablements-api.temporal-oms-enablements.svc.cluster.local:8050
```

Java fulfillment workers and the Python fulfillment worker receive this as `ENABLEMENTS_API_BASE_URL`.
The Python worker also receives `TEMPORAL_FULFILLMENT_ADDRESS`, `TEMPORAL_FULFILLMENT_NAMESPACE`, and `ANTHROPIC_API_KEY`.

## Local KinD or k3d

Start local Temporal and create the namespaces/endpoints/search attributes:

```bash
temporal server start-dev --ip 0.0.0.0 --ui-ip 0.0.0.0
./scripts/setup-temporal-namespaces.sh
```

Deploy the full local stack to KinD:

```bash
OVERLAY=local ./scripts/kind/demo-up.sh
```

Deploy the full local stack to k3d:

```bash
OVERLAY=local ./scripts/k3d/demo-up.sh
```

Port-forward APIs:

```bash
./scripts/kind/tunnel.sh
./scripts/k3d/tunnel.sh
```

Endpoints:

- Apps API: `http://localhost:8080/api/actuator/health`
- Processing API: `http://localhost:8070/actuator/health`
- Enablements API: `http://localhost:8050/actuator/health`
- Web UI: `http://localhost:3000` (Traefik `webui` entrypoint; routes `/api/v1/integrations` and
  `/api/v1/enablements` to `enablements-api`, the rest of `/api` to `apps-api`, `/` to the web pod)

`scripts/kind/app-deploy.sh` and `scripts/k3d/app-deploy.sh` default to
`PROCESSING_WORKER_MODE=versioned`, preserving the Temporal Worker Controller path for the
processing worker. To deploy processing as a plain Kubernetes `Deployment` from `k8s/base`, run:

```bash
PROCESSING_WORKER_MODE=deployment OVERLAY=local ./scripts/kind/app-deploy.sh
PROCESSING_WORKER_MODE=deployment OVERLAY=local ./scripts/k3d/app-deploy.sh
```

## Temporal Cloud

Populate the secret files under `config/` from their templates, then deploy with:

```bash
OVERLAY=cloud ./scripts/kind/demo-up.sh
OVERLAY=cloud ./scripts/k3d/demo-up.sh
```

Cloud overlay defaults currently use:

- Apps namespace: `fde-oms-apps.sdvdw`
- Processing namespace: `fde-oms-processing.sdvdw`
- Fulfillment namespace: `fde-oms-fulfillment.sdvdw`
- Enablements namespace: `default`

Update `k8s/overlays/cloud/configmap/*.yaml` and `k8s/overlays/cloud/kustomization.yaml` if your Temporal Cloud namespace names differ.

## Validate Manifests

```bash
kustomize build k8s/base
kustomize build k8s/overlays/local
kustomize build k8s/overlays/cloud
kustomize build k8s/processing-versioned/overlays/local
kustomize build k8s/processing-versioned/overlays/cloud
```

If `kustomize` is not installed, use `kubectl kustomize` with the same paths.
