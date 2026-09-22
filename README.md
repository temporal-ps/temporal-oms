# ACME Order Management System

A reference application for a fictional clothing retailer's Order Management System, built progressively with Temporal. This is the foundation for hands-on workshops that show how Temporal applications are designed, evolved, and operated in production.

---

## What This Application Teaches

The application is structured as two evolving versions of the same business problem. Each version adds a new layer of Temporal capability, and the workshops take you through real operational scenarios against live running code.

### Temporal Concepts Covered

| Concept | Where |
|---------|-------|
| Signals, timers, activities | `apps.Order` and `processing.Order` workflows |
| Rate limiting via worker options | Commerce App validation (`processing` context) |
| Worker Deployments (versioning) | All workers — foundation for the rollout workshop |
| Nexus cross-namespace operations | `apps.Order` → `fulfillment.Order` |
| `UpdateWithStart` | `fulfillment.Order` early-start pattern |
| Child workflow lifecycle | ShippingAgent scoped to `apps.Order` |
| LLM agent integration | `ShippingAgent` reliability harness (Python) |
| Workflow pinning and draining | v1 → v2 ownership transfer |
| Temporal Worker Controller | Kubernetes-automated rollout (Part 2 of handoff workshop) |

### Workshops

| Workshop | Goal |
|---------|------|
| [Safely Move Fulfillment Ownership](workshop/safe-fulfillment-handoff/README.md) | Transfer ownership of fulfillment between bounded contexts under live traffic without feature flags or downtime — first manually via CLI, then automated via the Temporal Worker Controller on Kubernetes |
| [Observe the ShippingAgent Reliability Harness](workshop/observe-shipping-agent/README.md) | Trace an AI-assisted shipping recommendation end to end, connecting the advisory LLM boundary to durable workflow state via Search Attributes |

---

## The Application

ACME's OMS processes clothing orders through three phases:

| Phase           | Description                                                            |
|-----------------|------------------------------------------------------------------------|
| **Accumulate**  | Accumulate order inputs from Commerce App and Payment Processor        |
| **Processing**  | Validate, enrich, and coordinate order data across downstream services |
| **Fulfillment** | Allocate inventory, select carrier, generate label, track delivery     |

### v1 — Order Processing

Temporal replaces a fragile Kafka-based consumer chain. The `processing.Order` workflow aggregates inputs that arrive out of order, validates order data against a rate-limited Commerce App API, waits up to 30 days for Payment Capture, and emits an enriched order to fulfillment.

→ [PRD: v1 Order Processing](docs/prd/v1-order-processing.md)

### v2 — Smart Fulfillment

An LLM-driven `ShippingAgent` replaces the static downstream Kafka consumer. The `apps.Order` workflow starts `fulfillment.Order` early via Nexus (`UpdateWithStart`), then hands off the enriched order once processing completes. The agent re-shops carrier rates at fulfillment time to protect margins and route around supply chain disruptions.

→ [PRD: v2 Smart Fulfillment](docs/prd/v2-smart-fulfillment.md)

### Architecture

Orders flow across two Temporal namespaces — `apps` and `processing` — connected to the fulfillment bounded context via Nexus:

```
Commerce App ──→  apps namespace           ──Nexus──→  fulfillment (Python)
                  apps.Order workflow                   fulfillment.Order workflow
                  (Java)                                ShippingAgent workflow

Payment Processor → processing namespace
                    processing.Order workflow
                    (Java)
```

→ Full requirements and evolution: [docs/prd/README.md](docs/prd/README.md)

A web UI for order management and observability is in development.

---

## Getting Started

### Tool Prerequisites

Preferred setup uses [asdf](https://asdf-vm.com/guide/getting-started.html) to install the tools
listed in [`.tool-versions`](.tool-versions). Review asdf and
[`scripts/setup-asdf-plugins.sh`](scripts/setup-asdf-plugins.sh) before running this path if you
want to audit what will execute.

```bash
./scripts/setup-asdf-plugins.sh
asdf install
```

What the preferred path does:

- `./scripts/setup-asdf-plugins.sh` reads `.tool-versions`, checks `asdf plugin list`, and runs
  `asdf plugin add <tool>` for each missing asdf plugin, using your installed asdf's plugin index.
- `asdf install` then uses those plugins to download, build when required, and install the pinned
  tool versions from `.tool-versions` into your local asdf installation.
- This path changes your local asdf plugin and tool directories. It does not install tools that are
  not listed in `.tool-versions`, and it does not install Docker, Temporal CLI, curl, or yq.

The table below shows what is in the pinned toolchain, what the local process path needs, and what
the full Kubernetes paths need. ✅ means included or required; a blank cell means not needed for
that column.

| Dependency | Pinned in `.tool-versions` | Local run | K8s paths | Purpose |
|------------|----------------------------|-----------|-----------|---------|
| `java` (OpenJDK 21) | ✅ | ✅ | ✅ | Build and run Java services |
| `maven` 3.9+ | ✅ | ✅ | ✅ | Java build tool |
| `python` | ✅ | ✅ | | Runtime for local Python fulfillment worker |
| `uv` | ✅ | ✅ | | Python dependency manager for local worker |
| `temporal` CLI | | ✅ | ✅ | Temporal dev server, namespace, workflow, and Nexus setup |
| `curl` | | ✅ | ✅ | Readiness checks and API smoke tests |
| `xh` | ✅ | ✅ | ✅ | HTTP client used by scenario scripts |
| `docker` | | | ✅ | Container runtime for KinD and k3d |
| `kind` | ✅ | | ✅ | Local Kubernetes cluster runner |
| `k3d` | ✅ | | ✅ | Lightweight local Kubernetes cluster runner |
| `kubectl` | ✅ | | ✅ | Kubernetes control plane |
| `helm` | ✅ | | ✅ | Install Temporal Worker Controller |
| `yq` | | | ✅ | Parse cloud secret YAML in k8s scripts |
| `k9s` | ✅ | | | Optional Kubernetes cluster UI |
| `nodejs` | ✅ | | | Optional web UI tooling |
| `buf` | ✅ | | | Optional protobuf linting and generation |
| `caddy` | ✅ | | | Optional instructor key distribution helper |
| `cloudflared` | ✅ | | | Optional instructor key distribution helper |

Local run means `temporal server start-dev` plus `./scripts/local-up.sh`. K8s paths mean
`scripts/kind/*` or `scripts/k3d/*`, with either local Temporal or Temporal Cloud. Tools with a
blank `.tool-versions` cell are not installed by `asdf install`; install them separately when your
chosen path requires them.

---

## Steps to Temporal Deployment Maturity

Start at Level 1 and work up. Each level builds on the previous.

| Level | Description | What you need |
|-------|-------------|---------------|
| **1** | Run locally, no Kubernetes | JDK 21, Maven, Temporal CLI, Python with `uv`, `curl`; `xh` for scenarios |
| **2** | KinD or k3d cluster with local Temporal | Level 1 + Docker, KinD or k3d, Helm, kubectl, yq; k9s optional |
| **3** | KinD or k3d cluster connected to Temporal Cloud | Level 2 + Temporal Cloud account, namespaces, service accounts, API keys |
| **4** | Worker Versioning Enablement (live demo) | Level 3 running + load generation |

---

## Level 1 — Run Locally (No Kubernetes)

Fastest path to a working system. All services run as local processes against a local Temporal
server.

Terminal 1:

```bash
./scripts/start-temporal-dev.sh
```

This wraps `temporal server start-dev` with the dynamic-config flags standalone Nexus
operations and standalone activities need; see
**[docs/GETTING_STARTED.md](docs/GETTING_STARTED.md)** for details.

Terminal 2:

```bash
# bring up all APIs, workers, local namespaces, Nexus endpoints, and Worker Versioning state
./scripts/local-up.sh

# Optional dry run to see it all work
./scripts/runscenario.sh valid-order --yes

# tear down all local services except Temporal server
./scripts/local-down.sh
```

Stop the Temporal dev server with `Ctrl+C` in Terminal 1.

- **[docs/GETTING_STARTED.md](docs/GETTING_STARTED.md)**: local setup, demo scenarios, troubleshooting
- **[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)**: protobuf changes, workflow modifications, debugging, testing

---

## Level 2 — Kubernetes Deployment with Local Temporal

Full stack in a local Kubernetes cluster. Choose one runner and use that directory consistently.

```bash
# Terminal 1
./scripts/start-temporal-dev.sh --ip 0.0.0.0 --ui-ip 0.0.0.0

# Terminal 2
./scripts/setup-temporal-namespaces.sh
./scripts/kind/infra-up.sh
./scripts/kind/app-deploy.sh

# or
./scripts/setup-temporal-namespaces.sh
./scripts/k3d/infra-up.sh
./scripts/k3d/app-deploy.sh
```

→ **[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)** for full prerequisites, verification, and troubleshooting.

---

## Level 3 — Kubernetes + Temporal Cloud

### Step 1: Set Up Temporal Cloud (Manual, One-Time)

#### a) Create Temporal Namespaces

In [Temporal Cloud](https://cloud.temporal.io) > Namespaces, create:

| Namespace | Purpose |
|-----------|---------|
| `apps` | Order orchestration and data collection |
| `processing` | Order validation and enrichment |
| `fulfillment` | Fulfillment workflow and ShippingAgent workers |

Your fully-qualified namespace names will be `<namespace-name>.<account-id>`.

The cloud overlay currently points enablements workers at `default`. If your account does not have
that namespace, create or choose an enablements namespace and update the cloud configmaps before
deploying.

#### b) Create Service Accounts and API Keys

In Temporal Cloud > Settings > Identities, create four service accounts:

| Service Account | Role | Used by |
|----------------|------|---------|
| `acme-apps-service-account` | Developer | `apps` Spring workers |
| `acme-processing-service-account` | Developer | `processing` Spring workers |
| `acme-fulfillment-service-account` | Developer | `fulfillment` Spring and Python workers |
| `acme-automations-service-account` | Developer or Admin | Temporal Worker Controller |

> **Why a separate automations account?** The Worker Controller calls Temporal's Worker Deployment API to register build-ids and manage traffic ramp. This requires broader permissions than a standard worker connection. Keep it separate so it can be rotated independently.

For each service account, generate an API key. Copy the values. They are shown only once.

#### c) Create Nexus Endpoints

In Temporal Cloud > Nexus, create:

| Endpoint name | Target namespace | Target task queue |
|--------------|----------------|-----------------|
| `oms-processing-v1` | `processing` | `processing` |
| `oms-apps-v1` | `apps` | `apps` |
| `oms-integrations-v1` | `default` or your enablements namespace | `integrations` |
| `oms-fulfillment-v1` | `fulfillment` | `fulfillment` |
| `oms-fulfillment-agents-v1` | `fulfillment` | `agents` |

Use fully-qualified namespace names, for example `apps.<account-id>`. The
`scripts/setup-temporal-namespaces.sh` helper is for local Temporal unless it is extended with Cloud
API key and TLS flags.

#### d) Note Your Region Endpoint

Find your region in Temporal Cloud > Namespaces > (select namespace) > Connection:

```
<your-region>.aws.api.temporal.io:7233
```

### Step 2: Create Local Secret Files

Copy the templates and fill in your API keys. These files are gitignored. Never commit them.

```bash
cp config/acme.apps.secret.template.yaml        config/acme.apps.secret.yaml
cp config/acme.processing.secret.template.yaml  config/acme.processing.secret.yaml
cp config/acme.fulfillment.secret.template.yaml config/acme.fulfillment.secret.yaml
cp config/acme.automations.secret.template.yaml config/acme.automations.secret.yaml
```

Edit each file and replace `<TEMPORAL CLOUD API KEY>` with the corresponding service account's API key.

### Step 3: Update Cloud ConfigMaps

In `k8s/overlays/cloud/configmap/`, update the Temporal namespace and endpoint values to match your account. Look for `<your-account-id>` and `<your-region>` placeholders.

### Step 4: Deploy

```bash
OVERLAY=cloud ./scripts/kind/infra-up.sh    # Creates KinD cluster, installs controller, applies secrets
OVERLAY=cloud ./scripts/kind/app-deploy.sh  # Builds images, deploys apps

# or
OVERLAY=cloud ./scripts/k3d/infra-up.sh
OVERLAY=cloud ./scripts/k3d/app-deploy.sh
```

→ **[docs/CLOUD.md](docs/CLOUD.md)** for verification steps and troubleshooting.

---

## Level 4 — Worker Versioning Enablement

Demonstrates zero-downtime worker version rollouts against a live order stream. The Temporal Worker Controller progressively shifts traffic from the old worker version to the new one while in-flight workflows drain cleanly.

**Requires:** Level 3 running with load flowing.

→ **[java/enablements/README.md](java/enablements/README.md)** for the `processing`-only,
CLI-driven version of this demo.

→ **[docs/ADMIN_WORKER_VERSIONS.md](docs/ADMIN_WORKER_VERSIONS.md)** for the Admin UI
walkthrough: promote apps, processing, and fulfillment together by OMS version, or one
bounded context at a time.

---

## Scripts Reference

| Script | Purpose |
|--------|---------|
| `scripts/setup-asdf-plugins.sh` | Install missing asdf plugins listed in `.tool-versions` |
| `scripts/kind/infra-up.sh` | Create KinD cluster, install Temporal Worker Controller, apply cloud secrets |
| `scripts/k3d/infra-up.sh` | Create k3d cluster, install Temporal Worker Controller, apply cloud secrets |
| `scripts/kind/app-deploy.sh` | Build Docker images, load into KinD, deploy all applications |
| `scripts/k3d/app-deploy.sh` | Build Docker images, import into k3d, deploy all applications |
| `scripts/kind/deploy-processing-workers.sh` | Bump processing workers in KinD to a new version (`VERSION=v2`) |
| `scripts/k3d/deploy-processing-workers.sh` | Bump processing workers in k3d to a new version (`VERSION=v2`) |
| `scripts/kind/demo-up.sh` | Run KinD infra-up + app-deploy in one step |
| `scripts/k3d/demo-up.sh` | Run k3d infra-up + app-deploy in one step |
| `scripts/kind/infra-down.sh` | Tear down the KinD cluster |
| `scripts/k3d/infra-down.sh` | Tear down the k3d cluster |
| `scripts/kind/status.sh` | Show pod status across namespaces in KinD |
| `scripts/k3d/status.sh` | Show pod status across namespaces in k3d |
| `scripts/local-up.sh` | Start all local OMS APIs and workers |
| `scripts/local-down.sh` | Stop services started by `local-up.sh` |
| `scripts/setup-temporal-namespaces.sh` | Create local Temporal namespaces, Nexus endpoints, search attributes, and current Worker Deployment versions |
| `scripts/kind/tunnel.sh` | Port-forward APIs for local access through KinD |
| `scripts/k3d/tunnel.sh` | Port-forward APIs for local access through k3d |

→ **[scripts/README.md](scripts/README.md)** for detailed usage.

---

## Documentation

| Document | Contents |
|----------|----------|
| [docs/prd/README.md](docs/prd/README.md) | Business requirements and full application scope |
| [docs/prd/v1-order-processing.md](docs/prd/v1-order-processing.md) | v1 PRD: Processing phase requirements and data specs |
| [docs/prd/v2-smart-fulfillment.md](docs/prd/v2-smart-fulfillment.md) | v2 PRD: Smart Fulfillment with LLM Shipping Agent |
| [docs/GETTING_STARTED.md](docs/GETTING_STARTED.md) | Local setup, demo scenarios, and troubleshooting |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Kubernetes deployment (Level 2 and 3) |
| [docs/CLOUD.md](docs/CLOUD.md) | Temporal Cloud verification and troubleshooting |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | Protobuf changes, workflow modifications, testing |
| [docs/ADMIN_WORKER_VERSIONS.md](docs/ADMIN_WORKER_VERSIONS.md) | Admin UI walkthrough for worker version rollout (Level 4) |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | Version history |
