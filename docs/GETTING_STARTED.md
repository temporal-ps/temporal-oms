# Getting Started with Temporal OMS

Use this path first when you want a local system running quickly. It uses local processes only:
no Kubernetes, no Docker, and no Temporal Cloud account.

## Fastest Local Start

Required commands for a fresh clone:

- JDK 21 (`java` and `javac`)
- Maven 3.9+
- Temporal CLI
- `uv` with Python 3.10+ for the Python fulfillment worker
- `curl` for readiness checks
- `xh` for the bundled scenario scripts

Docker, KinD, k3d, kubectl, Helm, k9s, yq, buf, Node.js, and Temporal Cloud credentials are not
required for this local path. Node.js is only needed if you run the web UI. `buf` is only needed if
you regenerate protobuf contracts.

In Codespaces, these tools are already installed. On your machine,
[asdf](https://asdf-vm.com/guide/getting-started.html) can install the pinned asdf-managed versions
from `.tool-versions` after `./scripts/setup-asdf-plugins.sh` adds the matching plugins. Install
the Temporal CLI separately and manually install only the local subset if you want the smallest
setup.

```bash
./scripts/setup-asdf-plugins.sh
asdf install
```

The first run may download Maven and Python dependencies unless they are already cached. You will
use **two terminals** to run this local setup.

### 1. Clone GitHub Repository

```bash
git clone https://github.com/temporal-ps/temporal-oms.git
cd temporal-oms
```

### 2. Start Temporal

Run this in one terminal and leave it running:

```bash
./scripts/start-temporal-dev.sh
```

This wraps `temporal server start-dev` with the dynamic-config flags standalone Nexus
operations and standalone activities need (Nexus callback endpoint template, allowed callback
addresses, update callbacks, and the two `enableStandalone` flags). Pass through any extra
`start-dev` flags, e.g. `./scripts/start-temporal-dev.sh --ip 0.0.0.0 --ui-ip 0.0.0.0`.

Temporal UI will be available at `http://localhost:8233`.

### 3. Start OMS

Run this from the repo root in *another terminal*:

```bash
./scripts/local-up.sh
```

`local-up.sh` verifies Temporal is reachable, creates the local namespaces, registers Nexus
endpoints, sets local Worker Deployment versions, and starts the APIs, Java workers, enablements
workers, fulfillment workers, and Python fulfillment worker. On a fresh clone, it builds missing
Java artifacts before starting services.

### Optional Dry Run

Run the valid-order scenario between `local-up.sh` and `local-down.sh`:

```bash
./scripts/runscenario.sh valid-order --yes
```

Then open `http://localhost:8233` and inspect the order workflows in the `apps`, `processing`, and
`fulfillment` namespaces.

### 4. Stop OMS

```bash
./scripts/local-down.sh
```

Stop the Temporal dev server with `Ctrl+C` in its own terminal.

---

## Other Setup Paths

### Running in GitHub Codespaces?

Use this repo's devcontainer. It installs the workshop toolchain for you:

- Java 21 and Maven 3.9.9
- Python 3.13 and `uv`
- Temporal CLI `1.7.0`
- Docker-in-Docker
- `kubectl`, Helm, `kind`, `k3d`, and `k9s`
- `jq`, `yq`, `curl`, and process utilities

Create a Codespace from the workshop branch. The devcontainer also creates `.env.local` from the
committed non-secret `.env.codespaces` file and prebuilds Java/Python dependencies.

Instructor-only key distribution helpers (`caddy` and `cloudflared`) run from the instructor's
local machine, not from attendee Codespaces.

### Deploying to Kubernetes?

If you want to run the full application stack in Kubernetes (locally via KinD/k3d or with Temporal Cloud), see **[DEPLOYMENT.md](DEPLOYMENT.md)** for:
- Parallel Kubernetes runners under `scripts/kind/*` and `scripts/k3d/*`
- Support for both local Temporal and Temporal Cloud
- Production-like Kubernetes environment
- Traefik ingress for API access

---

## Prerequisite Details

### Local Required Tools

These are enough for `temporal server start-dev` plus `./scripts/local-up.sh`.

1. **JDK 21**
   ```bash
   java --version  # Must be 21+
   javac --version # Must be 21+
   ```

2. **Maven 3.9+**
   ```bash
   mvn --version
   ```

3. **Temporal CLI**
   ```bash
   # macOS
   brew install temporal

   # Or from: https://github.com/temporalio/cli/releases
   temporal --version
   ```

4. **curl**
   ```bash
   curl --version
   ```

5. **uv** (Python package manager, for the Python fulfillment worker)
   ```bash
   # macOS
   brew install uv

   # Or: curl -LsSf https://astral.sh/uv/install.sh | sh

   # Optional warm-up, run from the repo root
   uv sync --project python
   ```

6. **xh** (only for bundled scenario scripts)
   ```bash
   # macOS
   brew install xh

   xh --version
   ```

Docker, Kubernetes tools, Temporal Cloud credentials, Node.js, and `buf` are outside the local
startup path. Install them only when you work on the matching path: Kubernetes deployment, web UI,
or protobuf generation.

### Local Ports

The local process stack uses fixed ports. Make sure these are free before running
`./scripts/local-up.sh`:

| Port | Used by |
|------|---------|
| `8050`, `9050` | `enablements-api` and management |
| `8060`, `9071` | `fulfillment-api` and management |
| `8061`, `9072` | `fulfillment-workers` and management |
| `8070`, `9081` | `processing-api` and management |
| `8071`, `9082` | `processing-workers` and management |
| `8080`, `9091` | `apps-api` and management |
| `8081`, `9092` | `apps-workers` and management |
| `9052` | `enablements-workers` management |

### Optional Environment File

`scripts/local-up.sh` creates `.env.local` from `.env.codespaces` when `.env.local` is missing.
If you want to manage local defaults yourself, copy the environment template before starting
services:

```bash
cp .env.example .env.local
```

All Java services and Python workers load `.env.local` automatically. The defaults in
`.env.example` are already correct for local Temporal, with no API keys required for Temporal
itself.

For Codespaces runs, use the committed non-secret defaults instead:

```bash
cp .env.codespaces .env.local
```

Keep `ANTHROPIC_API_KEY` and `OPENAI_API_KEY` out of `.env.local` in Codespaces. Provide them as GitHub Codespaces secrets so they are exposed as runtime environment variables.

API keys are only needed for integration features. Workers start and connect without them. Activities
that call external APIs will fail with a clear error message if the key is missing when that feature
is exercised.

| Variable | Where to get it | Needed for |
|----------|----------------|------------|
| `EASYPOST_API_KEY` | [easypost.com](https://www.easypost.com) → Dashboard → API Keys | Address verification, carrier rate quotes |
| `ANTHROPIC_API_KEY` | [console.anthropic.com](https://console.anthropic.com) | AI shipping agent live Claude calls |
| `OPENAI_API_KEY` | [platform.openai.com](https://platform.openai.com) | AI exercises or tooling that calls OpenAI APIs |
| `PREDICTHQ_API_KEY` | [predicthq.com](https://www.predicthq.com) | Location risk events (weather/event disruption data) |

## What The Local Scripts Do

`scripts/setup-temporal-namespaces.sh` creates the local Temporal namespaces, Nexus endpoints,
fulfillment search attributes, and current Worker Deployment versions.

`scripts/local-up.sh` starts:

- `apps-api` on `http://localhost:8080`
- `processing-api` on `http://localhost:8070`
- `fulfillment-api` on `http://localhost:8060`
- `enablements-api` on `http://localhost:8050`
- Java workers for `apps`, `processing`, `fulfillment`, and `enablements`
- Python fulfillment workers for the shipping agent path

`scripts/local-down.sh` stops the OMS services started by `local-up.sh`. Stop the Temporal dev
server separately with `Ctrl+C`.

The setup script is safe to rerun. Worker Versioning is enabled in the local workers, so the script
also sets build-id `local` as current for the `apps`, `processing`, and `fulfillment` deployments.

---

## Demo Scenarios

Once services are running, try the valid-order dry run:

```bash
./scripts/scenarios/valid-order/1-submit-order.sh
./scripts/scenarios/valid-order/2-capture-payment.sh
```

Other scenarios are available through the selector:

```bash
./scripts/runscenario.sh
./scripts/runscenario.sh invalid-order --yes
./scripts/runscenario.sh cancel-order --yes
```

See `scripts/scenarios/README.md` for detailed demo instructions.

---

## Web UI: Commerce + Payments Demo

The fastest way to see what the OMS is about: a Svelte storefront backed by
a simulated Commerce App and Payments Processor (both inside
`enablements-api`/`enablements-workers`, already running from
`./scripts/local-up.sh`).

### 1. Install and start the web app

Requires Node.js (see `web/package.json` for the toolchain; npm ships with
Node). From the repo root, in a third terminal:

```bash
cd web
npm install
npm run dev
```

Open `http://localhost:3000`.

### 2. Walk through checkout

1. **Home** (`/shop/home`): enter any customer ID (e.g. `demo-customer`) and continue.
2. **Clothing**: browse the catalog (live stock counts come from the `CommerceInventory` workflow) and add an item to your cart.
3. **Cart**: click "Place Order". A floating scenario selector appears
   (bottom-right): `Normal`, `Payment before commerce`, `Missing commerce
   event`, `Missing payment event`. These drive how `PublishCartOrders`
   delivers this order's webhook events on its next scheduled tick (every
   10s); pick one to see the effect, or leave it on `Normal`.
4. Fill in a shipping address and a card number. Card number determines the
   payment outcome:

   | Card number | Outcome |
   |---|---|
   | any unrecognized number (e.g. `4242424242424242`) | Authorizes, auto-captures in ~5s |
   | `4000000000000002` | Declined |
   | `4000000000009995` | Insufficient funds |
   | `4000000000000069` | Expired card |
   | `4000000000000259` | Authorizes, then the capture attempt fails |

   A declined/failing card leaves the checkout form open with an error so
   you can retry with a different number.
5. On success you land on **My Orders**, showing the order and charge status.

### 3. Inspect webhook delivery

`PublishCartOrders` delivers events on a 10-second Temporal Schedule.
Watch it happen:

```bash
curl -s http://localhost:8050/api/v1/integrations/webhooks/events | jq
```

Or trigger one scenario directly without the UI:

```bash
./scripts/simulate-scenario.sh NORMAL
./scripts/simulate-scenario.sh PAYMENT_BEFORE_COMMERCE
```

See `SPECS/commerce-payments-apps/spec.md` for the full design.

---

## Review Orders Sent for Fulfillment
To view orders sent to Kafka for fulfillment, navigate to:

`http://localhost:8071/admin/order-fulfillment/<orderId>`

For example, the order fulfillment message created by the "Valid order (happy path)" scripts can be viewed by navigating to:

`http://localhost:8071/admin/order-fulfillment/valid-order-123`

**Note:**
This is only for demonstration purposes and not for production. It shows the fulfillment message was added to the Kafka topic.  At this time, it is only available when running the application locally (Level 1 - No Kubernetes, No Cloud).  
---

## API Endpoints

### Submit Order
```bash
xh PUT http://localhost:8080/api/v1/commerce-app/orders/{orderId} \
  customerId="cust-001" \
  order:='{"orderId":"...","items":[...],"shippingAddress":{...}}'
```

### Capture Payment
```bash
xh POST http://localhost:8080/api/v1/payments-app/orders \
  customerId="cust-001" \
  rrn="payment-intent-123" \
  amountCents=9999 \
  metadata:='{"orderId":"..."}'
```

### Check Order Status
```bash
# Via Temporal UI
open http://localhost:8233/namespaces/apps/workflows/{orderId}

# Or via CLI
temporal workflow describe \
  --workflow-id {orderId} \
  --namespace apps
```

---

## Project Structure

```
java/
├── apps/
│   ├── apps-api/              # REST API server (port 8080)
│   ├── apps-core/             # Workflows & activities
│   └── apps-workers/          # Worker process
├── processing/
│   ├── processing-core/       # Workflows & activities
│   └── processing-workers/    # Worker process
├── oms/                       # Shared config
└── generated/                 # Generated protobuf code

scripts/
├── local-up.sh                # Start all local OMS services
├── local-down.sh              # Stop services started by local-up.sh
├── scenarios/                 # Demo scenario scripts
└── setup-temporal-namespaces.sh
```

---

## Architecture Overview

### Apps Namespace
- **CompleteOrder Workflow** — Orchestrates order from start to finish
- **Updates** — Handles incoming order and payment data
- **Nexus Calls** — Forwards complete order to Processing

### Processing Namespace
- **ProcessOrder Workflow** — Validates, enriches, fulfills order
- **SupportTeam Workflow** — Handles manual corrections for invalid orders
- **Activities** — Validation, enrichment, fulfillment logic

### Communication
- **UpdateWithStart** — Apps accumulates commerce + payment data atomically
- **Nexus** — Apps initiates ProcessOrder in Processing namespace
- **Async Activities** — Support team can correct orders without blocking

---

## Troubleshooting

### ❌ `INVALID_ARGUMENT: versioning behavior cannot be specified without deployment options`

This error means a worker registered a workflow type with a `@WorkflowVersioningBehavior` annotation but the worker itself does not have `deployment-properties` (i.e. `use-versioning: true`) configured.

The `@WorkflowVersioningBehavior` annotation is compiled into the workflow type registration and is always sent to the server — it cannot be disabled via Spring config or environment variables. If the worker config doesn't declare a deployment, the server rejects it.

**Fix**: Ensure the worker running this workflow has `deployment-properties.use-versioning: true` in its config, and that a current version has been set for that deployment.

### ❌ "Failed to connect to localhost:4317" (OpenTelemetry Error)

This is expected in development. OpenTelemetry is trying to send metrics but there's no collector.

**Fix option 1** — Disable OpenTelemetry:
```yaml
# In application.yaml
otel.sdk.disabled: true
```

**Fix option 2** — Start OpenTelemetry collector:
```bash
docker run -p 4317:4317 otel/opentelemetry-collector:latest
```

### ❌ "Connection refused" on API calls

```bash
# Is Apps API running?
curl http://localhost:8080/actuator/health

# Is Temporal server running?
temporal operator namespace list
```

### ❌ "Workflow not found" on temporal commands

```bash
# Is worker running in correct namespace?
temporal workflow list --namespace apps

# Did you run the setup script?
./scripts/setup-temporal-namespaces.sh
```

### ❌ Workflows aren't processing orders

1. Both Apps and Processing workers are running
2. Both namespaces exist: `temporal operator namespace list`
3. Nexus endpoints are registered: `temporal operator nexus endpoint list`
4. Order ID doesn't contain "invalid" (that's a special test case)

### ❌ Workers are connected but orders never progress (tasks not dispatched)

This is the Worker Versioning routing problem. Workers with `deployment-properties` configured will connect and poll successfully but receive no tasks if a current version has not been set.

**Fix**: Re-run the setup script (it is idempotent):
```bash
./scripts/setup-temporal-namespaces.sh
```

Or set the version directly:
```bash
temporal worker deployment set-current-version \
  --deployment-name processing \
  --build-id local \
  --allow-no-pollers \
  --namespace processing
```

Verify the current version is set:
```bash
temporal worker deployment describe \
  --name processing \
  --namespace processing
```

---

## Next Steps

1. ✅ Run the valid order scenario from `scripts/scenarios/valid-order/`
2. ✅ Run the invalid order scenario to see manual corrections
3. ✅ Observe workflows in Temporal UI at http://localhost:8233
4. ✅ Modify a workflow and restart the worker — see [DEVELOPMENT.md](DEVELOPMENT.md)
5. ✅ Deploy to Kubernetes — see [DEPLOYMENT.md](DEPLOYMENT.md)

---

## Resources

- **[DEVELOPMENT.md](DEVELOPMENT.md)** — Protocol Buffers, workflow changes, debugging, testing
- **[DEPLOYMENT.md](DEPLOYMENT.md)** — Kubernetes deployment (KinD + Temporal Cloud)
- **[Temporal Documentation](https://docs.temporal.io/)** — Complete SDK docs
- **[domain/apps/README.md](../domain/apps/README.md)** — Apps context details
- **[domain/processing/README.md](../domain/processing/README.md)** — Processing context details
- **[scripts/scenarios/README.md](../scripts/scenarios/README.md)** — Demo scenarios
