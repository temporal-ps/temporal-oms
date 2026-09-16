# Demo Scripts

Modular scripts for managing the Temporal OMS Kubernetes deployment with either KinD or k3d.

> For complete deployment documentation including Temporal Cloud API key setup, see [../docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md).

## Runtime Paths

| Path | Purpose |
|---|---|
| `scripts/kind/*` | KinD implementation |
| `scripts/k3d/*` | k3d implementation |

The two cluster-specific directories intentionally duplicate the small driver scripts. This keeps
the operational paths easy to read and avoids cluster-driver conditionals inside every shell script.

## Quick Start

Local process stack:

```bash
# Terminal 1
./scripts/start-temporal-dev.sh
```

```bash
# Terminal 2, from the repo root
./scripts/local-up.sh

# Optional dry run
./scripts/runscenario.sh valid-order --yes

./scripts/local-down.sh
```

KinD with Temporal Cloud:

```bash
OVERLAY=cloud ./scripts/kind/demo-up.sh
```

k3d with Temporal Cloud:

```bash
OVERLAY=cloud ./scripts/k3d/demo-up.sh
```

KinD with local Temporal:

```bash
# Terminal 1
./scripts/start-temporal-dev.sh --ip 0.0.0.0 --ui-ip 0.0.0.0

# Terminal 2
./scripts/setup-temporal-namespaces.sh
OVERLAY=local ./scripts/kind/demo-up.sh
```

k3d with local Temporal:

```bash
# Terminal 1
./scripts/start-temporal-dev.sh --ip 0.0.0.0 --ui-ip 0.0.0.0

# Terminal 2
./scripts/setup-temporal-namespaces.sh
OVERLAY=local ./scripts/k3d/demo-up.sh
```

For live coding after infrastructure is up:

```bash
OVERLAY=cloud ./scripts/kind/app-deploy.sh
OVERLAY=local ./scripts/k3d/app-deploy.sh
```

Tear everything down:

```bash
./scripts/kind/demo-down.sh
./scripts/k3d/demo-down.sh
```

## Modular Workflow

Use one directory consistently for a session:

```bash
./scripts/kind/infra-up.sh
./scripts/kind/tunnel.sh
OVERLAY=cloud ./scripts/kind/app-deploy.sh
./scripts/kind/app-down.sh
./scripts/kind/infra-down.sh
```

```bash
./scripts/k3d/infra-up.sh
./scripts/k3d/tunnel.sh
OVERLAY=local ./scripts/k3d/app-deploy.sh
./scripts/k3d/app-down.sh
./scripts/k3d/infra-down.sh
```

Check status anytime:

```bash
./scripts/kind/status.sh
./scripts/k3d/status.sh
```

Run demo scenarios:

```bash
./scripts/runscenario.sh
./scripts/runscenario.sh valid-order --yes
```

## Temporary Workshop API Key Distribution

For public-repo workshops where attendees should not bring their own API keys, the instructor can
serve a short-lived dotenv payload behind Basic Auth and a Cloudflare quick tunnel:

```bash
brew install caddy cloudflared
./scripts/serve-workshop-api-keys.sh
```

The script reads:

```text
~/.config/anthropic/tmp-replay-26-partners-day.key
~/.config/openai/tmp-replay-26-partners-day.key
```

It prints a random HTTPS URL, Basic Auth credentials, and the attendee command that appends the keys
to each attendee's gitignored `.env.local`. Stop the script after setup and revoke the provider keys
after the workshop.

For k3d/KinD runs, `scripts/k3d/app-deploy.sh` and `scripts/kind/app-deploy.sh` read `.env.local`
and patch `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` into the in-cluster `temporal-oms-secrets`
Secrets after Kustomize applies its placeholder values.

Defaults:

```text
local port: 7001
username: workshop
password: replay26-<4 hex chars>
path: /replay26.env
```

Override them when needed:

```bash
WORKSHOP_SECRET_USER=replay \
WORKSHOP_SECRET_PASSWORD=replay26 \
WORKSHOP_SECRET_PORT=7001 \
WORKSHOP_SECRET_PATH=/replay26.env \
./scripts/serve-workshop-api-keys.sh
```

The script writes and live-tails an access log so the instructor can see every request for the key
payload. Cloudflare quick tunnels always use a generated `trycloudflare.com` subdomain; use a named
Cloudflare tunnel and a domain you control if you need a stable hostname such as
`replay26.example.com`.

In an interactive terminal, the script pins the attendee access details at the top of the screen and
scrolls access logs underneath. Disable that terminal control if needed:

```bash
WORKSHOP_PIN_OUTPUT=false ./scripts/serve-workshop-api-keys.sh
```

## What Each Cluster Directory Provides

| Script | Purpose |
|---|---|
| `infra-up.sh` | Create cluster, create namespaces, install cert-manager, TWC, and Traefik |
| `app-deploy.sh` | Build Java projects, build Docker images, load/import images, deploy apps |
| `app-down.sh` | Remove applications while keeping the cluster running |
| `infra-down.sh` | Delete the cluster and all infrastructure |
| `demo-up.sh` | Full setup: runs `infra-up.sh` and `app-deploy.sh` |
| `demo-down.sh` | Full teardown: runs `app-down.sh` and `infra-down.sh` |
| `deploy-processing-workers.sh` | Build and deploy a new processing worker image through TWC |
| `tunnel.sh` | Port-forward APIs |
| `status.sh` | Show deployment status |

## Local Scripts

| Script | Purpose |
|---|---|
| `setup-asdf-plugins.sh` | Install missing asdf plugins listed in `.tool-versions` |
| `start-temporal-dev.sh` | Run `temporal server start-dev` with the dynamic-config flags standalone Nexus operations and standalone activities need |
| `local-up.sh` | Start all local OMS APIs and workers against an already running Temporal dev server |
| `local-down.sh` | Stop services started by `local-up.sh` |
| `setup-temporal-namespaces.sh` | Create local Temporal namespaces, Nexus endpoints, search attributes, and current Worker Deployment versions |

## Requirements

Install the asdf-managed toolchain:

```bash
./scripts/setup-asdf-plugins.sh
asdf install
```

Local process scripts:

- JDK 21 and Maven 3.9+
- Temporal CLI
- `uv` with Python 3.10+
- `curl`
- `xh` for scenario scripts

Cluster scripts:

- JDK 21 and Maven 3.9+ for Java builds
- Docker
- `kubectl`
- Helm
- yq
- `kind` for `scripts/kind/*`
- `k3d` for `scripts/k3d/*`
- k9s, optional
- Temporal CLI and a local Temporal dev server for `OVERLAY=local`, started with
  `./scripts/start-temporal-dev.sh` so standalone Nexus operations and standalone activities work

Cloud overlay scripts also require Temporal Cloud namespaces, service-account API keys, cloud
overlay config values, and the gitignored `config/*.secret.yaml` files described in
[../docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md).

## Overlays

Scripts support environment overlays through `OVERLAY`:

```bash
OVERLAY=cloud ./scripts/kind/demo-up.sh
OVERLAY=local ./scripts/kind/demo-up.sh
OVERLAY=local ./scripts/k3d/demo-up.sh
```

Default is `local` if `OVERLAY` is not set.

## Troubleshooting

Pods stuck in `ContainerCreating`:

```bash
./scripts/kind/status.sh
./scripts/k3d/status.sh
kubectl describe pod POD_NAME -n NAMESPACE
```

Need logs:

```bash
kubectl logs -n temporal-oms-apps -l app=apps-worker -f
kubectl logs -n temporal-oms-processing -l app=processing-workers -f
```

Reset everything:

```bash
./scripts/kind/demo-down.sh
./scripts/kind/demo-up.sh

./scripts/k3d/demo-down.sh
./scripts/k3d/demo-up.sh
```
