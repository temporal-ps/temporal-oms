# Getting Started with Temporal OMS

The web UI is the main way to use the OMS. There are two demos:

| Demo | Runs on | What you can do | Command |
|------|---------|-----------------|---------|
| [Demo 1](#demo-1-shop-and-watch-workflows) | Local processes | Place orders, watch workflows, drive load scenarios | `./scripts/local-up.sh` |
| [Demo 2](#demo-2-worker-version-rollout) | Local Kubernetes (k3d or KinD) | Everything in Demo 1, plus worker version rollout | `./scripts/k3d/demo-up.sh` |

Start with Demo 1. Neither demo needs a Temporal Cloud account.

## Prerequisites

| Tool | Demo 1 | Demo 2 | Purpose |
|------|--------|--------|---------|
| JDK 21 and Maven 3.9+ | ✅ | ✅ | Build and run the Java services |
| Temporal CLI | ✅ | ✅ | Temporal dev server and setup |
| `uv` (Python 3.10+) | ✅ | | Python fulfillment worker |
| Node.js | ✅ | | Web UI dev server |
| `curl` | ✅ | ✅ | Readiness checks |
| Docker, `k3d` (or `kind`), `kubectl`, Helm, `yq` | | ✅ | Local Kubernetes cluster |
| `xh` | optional | optional | [CLI scenario scripts](#verify-from-the-cli) |

[asdf](https://asdf-vm.com/guide/getting-started.html) installs the pinned versions from
`.tool-versions`. Install the Temporal CLI, Docker, `curl`, and `yq` separately (for example,
`brew install temporal`).

```bash
./scripts/setup-asdf-plugins.sh
asdf install
```

In GitHub Codespaces, the devcontainer installs all of these. See [Codespaces](#codespaces).

---

## Demo 1: Shop and watch workflows

```bash
./scripts/local-up.sh
```

This starts a Temporal dev server, creates the namespaces and Nexus endpoints, starts every API
and worker, and starts the web UI. The first run also builds the Java services and installs the
web UI dependencies, which takes a few minutes. When it finishes, open
**http://localhost:3000**.

If a Temporal server is already running at `127.0.0.1:7233`, `local-up.sh` uses it and leaves
it running on stop.

### Place an order

1. On the home page, click **Start Shopping**. Enter any customer ID (for example
   `demo-customer`).
2. **Clothing**: add an item to your cart. Stock counts come from the `CommerceInventory`
   workflow.
3. **Cart**: click **Place Order**. A scenario selector appears at the bottom right. It controls
   how this order's commerce and payment webhook events are delivered on the next 10-second
   `PublishCartOrders` tick:
   - `Normal`
   - `Payment before commerce`
   - `Missing commerce event`
   - `Missing payment event`
4. Enter a shipping address and a card number. The card number decides the payment outcome:

   | Card number | Outcome |
   |---|---|
   | Any other number (for example `4242424242424242`) | Authorizes, auto-captures in about 5s |
   | `4000000000000002` | Declined |
   | `4000000000009995` | Insufficient funds |
   | `4000000000000069` | Expired card |
   | `4000000000000259` | Authorizes, then the capture fails |

   A failing card keeps the checkout form open with an error, so you can retry with another
   number.
5. On success you land on **My Orders**, which shows the order, its charge, and a Temporal UI
   link for each stage.

### Watch it in Temporal

Open **http://localhost:8233**. Each order runs across these namespaces:

| Namespace | Workflows |
|-----------|-----------|
| `default` | Commerce App and Payments Processor simulators, load generator |
| `apps` | `Order` (one per order) |
| `processing` | Order validation and enrichment |
| `fulfillment` | Fulfillment and the shipping agent |

### Drive load from Admin

Click **Admin** in the top navigation. In the **Load Generator** panel, pick a shape and a
rate, then click **Start load**:

| Shape | Mix |
|-------|-----|
| Clean | Every order completes normally |
| Mixed | Mostly normal, with some webhook ordering problems and business exceptions |
| Chaos | A heavy mix of webhook problems and business exceptions |

The **Worker Versions** panel is disabled in Demo 1, and the badge at the top of the page says
"Running as local processes". Promotion creates and deletes Kubernetes Deployments, so it needs
[Demo 2](#demo-2-worker-version-rollout).

### Start over

Click **Temporal OMS** or **Home** in the header at any time. To clear all workflow history,
restart the stack: the dev server keeps history in memory.

```bash
./scripts/local-down.sh
./scripts/local-up.sh
```

### Stop

```bash
./scripts/local-down.sh
```

This stops the web UI, every service, and the Temporal dev server if `local-up.sh` started it.

---

## Demo 2: Worker version rollout

Demo 2 runs the OMS in a local k3d cluster against a Temporal dev server on your machine. The
Admin page's worker version controls work here. Stop Demo 1 first, because both demos use the
same ports.

```bash
./scripts/local-down.sh
./scripts/k3d/demo-up.sh
```

`demo-up.sh` does the following:

1. Starts a Temporal dev server bound to `0.0.0.0`, so cluster pods can reach it.
2. Creates the namespaces and Nexus endpoints. It does not register a Worker Deployment version,
   so the first rollout starts from a true OMS v1.
3. Creates the k3d cluster, builds the images, and deploys everything. The first run takes
   several minutes. Later runs resume the stopped cluster without rebuilding; set `REDEPLOY=1`
   to deploy current code.
4. Starts the port-forwards in the background.

Then:

1. Open **http://localhost:3000/admin**. The badge says "Running on Kubernetes (k3d/KinD)".
2. **Roll out OMS v1 before placing any orders or starting load.** Worker Deployments start
   with no current version, so workers receive no tasks until the first rollout. In the
   **OMS Version** panel, pick `v1` and click **Start rollout**.
3. Start load in the **Load Generator** panel, then roll out `v2`, `v3`, or `v4` while orders
   flow.

[ADMIN_WORKER_VERSIONS.md](ADMIN_WORKER_VERSIONS.md) explains what each rollout does, the
**Advanced** per-context controls, and how to demonstrate the unsafe pairing on purpose.

Stop:

```bash
./scripts/k3d/demo-down.sh          # stop the cluster; keeps it for a fast resume
HARD=1 ./scripts/k3d/demo-down.sh   # delete the cluster
```

Both stop the background port-forwards and the dev server that `demo-up.sh` started.

For KinD, use `scripts/kind/` in place of `scripts/k3d/`. For Temporal Cloud and step-by-step
cluster scripts, see [DEPLOYMENT.md](DEPLOYMENT.md).

---

## Verify from the CLI

The scenario scripts submit orders straight to the APIs. They work in both demos and need `xh`.

```bash
./scripts/runscenario.sh                        # pick a scenario interactively
./scripts/runscenario.sh valid-order --yes
./scripts/runscenario.sh invalid-order --yes
./scripts/runscenario.sh cancel-order --yes
```

Trigger one webhook delivery scenario without the UI:

```bash
./scripts/simulate-scenario.sh NORMAL
./scripts/simulate-scenario.sh PAYMENT_BEFORE_COMMERCE
```

Inspect webhook events:

```bash
curl -s http://localhost:8050/api/v1/integrations/webhooks/events | jq
```

Check an order:

```bash
temporal workflow describe --workflow-id <orderId> --namespace apps
```

In Demo 1 only, view the fulfillment message published to Kafka at
`http://localhost:8071/admin/order-fulfillment/<orderId>`.

See [scripts/scenarios/README.md](../scripts/scenarios/README.md) for each scenario.

---

## Reference

### `local-up.sh` options

| Option or variable | Effect |
|--------------------|--------|
| `--no-web` | Do not start the web UI; Node.js is then not required |
| `TEMPORAL_ADDRESS` | Temporal server to use or start (default `127.0.0.1:7233`) |
| `LOCAL_STARTUP_TIMEOUT` | Seconds to wait for each service (default `120`) |

Logs are in `.workshop/logs/` and PID files in `.workshop/run/`.

### Ports

Demo 1 uses these fixed ports. `local-up.sh` reports any that are busy before starting.

| Port | Used by |
|------|---------|
| `3000` | Web UI |
| `7233`, `8233` | Temporal dev server and Temporal UI |
| `8050`, `9050` | `enablements-api` and management |
| `8060`, `9071` | `fulfillment-api` and management |
| `8061`, `9072` | `fulfillment-workers` and management |
| `8070`, `9081` | `processing-api` and management |
| `8071`, `9082` | `processing-workers` and management |
| `8080`, `9091` | `apps-api` and management |
| `8081`, `9092` | `apps-workers` and management |
| `9052` | `enablements-workers` management |

### Environment file and API keys

`local-up.sh` creates `.env.local` from `.env.codespaces` when `.env.local` is missing. All Java
services and Python workers load it. To manage it yourself:

```bash
cp .env.example .env.local
```

API keys are only needed for integration features. Workers start without them, and an activity
that needs a missing key fails with a clear error when that feature runs.

| Variable | Where to get it | Needed for |
|----------|----------------|------------|
| `EASYPOST_API_KEY` | [easypost.com](https://www.easypost.com), Dashboard, API Keys | Address verification, carrier rate quotes |
| `ANTHROPIC_API_KEY` | [console.anthropic.com](https://console.anthropic.com) | Live Claude calls from the shipping agent |
| `OPENAI_API_KEY` | [platform.openai.com](https://platform.openai.com) | AI exercises or tooling that call OpenAI |
| `PREDICTHQ_API_KEY` | [predicthq.com](https://www.predicthq.com) | Location risk events |

### Codespaces

Create a Codespace from the workshop branch. The devcontainer installs the toolchain, creates
`.env.local` from `.env.codespaces`, and prebuilds the Java and Python dependencies. Provide
`ANTHROPIC_API_KEY` and `OPENAI_API_KEY` as Codespaces secrets, not in `.env.local`.

### API endpoints

```bash
# Submit an order
xh PUT http://localhost:8080/api/v1/commerce-app/orders/{orderId} \
  customerId="cust-001" \
  order:='{"orderId":"...","items":[...],"shippingAddress":{...}}'

# Capture payment
xh POST http://localhost:8080/api/v1/payments-app/orders \
  customerId="cust-001" \
  rrn="payment-intent-123" \
  amountCents=9999 \
  metadata:='{"orderId":"..."}'
```

---

## Troubleshooting

### Orders never progress (workers connected, no tasks dispatched)

Workers with Worker Versioning poll successfully but receive no tasks until their Worker
Deployment has a current version.

- **Demo 1:** `local-up.sh` sets build id `local` as current. Rerun the setup (it is
  idempotent):

  ```bash
  SKIP_VERSION_REGISTRATION=0 ./scripts/setup-temporal-namespaces.sh
  ```

- **Demo 2:** roll out an OMS version from the Admin page first.

Check a deployment's current version:

```bash
temporal worker deployment describe --name processing --namespace processing
```

### `INVALID_ARGUMENT: versioning behavior cannot be specified without deployment options`

A worker registered a workflow type annotated with `@WorkflowVersioningBehavior`, but the worker
has no `deployment-properties` (`use-versioning: true`). The annotation is always sent to the
server and cannot be disabled through configuration. Give that worker `deployment-properties`
and set a current version for its deployment.

### "Failed to connect to localhost:4317" (OpenTelemetry)

No OpenTelemetry collector is running. This is harmless in development. To silence it, set
`otel.sdk.disabled: true` in `application.yaml`, or run a collector:

```bash
docker run -p 4317:4317 otel/opentelemetry-collector:latest
```

### "Connection refused" on API calls

```bash
curl http://localhost:8080/actuator/health        # apps-api
temporal operator namespace list                  # Temporal server
```

In Demo 2, the port-forwards may have dropped after a pod restart. Check
`.workshop/logs/demo-tunnel.log`, then rerun `demo-up.sh`.

### A service failed during `local-up.sh`

`local-up.sh` prints the last 80 log lines of the failing service and stops everything it
started. Full logs are in `.workshop/logs/<service>.log`.

### Workflows are not processing orders

1. Check that both namespaces exist: `temporal operator namespace list`.
2. Check that the Nexus endpoints are registered: `temporal operator nexus endpoint list`.
3. An order ID that contains `invalid` fails validation on purpose.

---

## Next

- [DEVELOPMENT.md](DEVELOPMENT.md): protobuf changes, workflow changes, debugging, testing
- [DEPLOYMENT.md](DEPLOYMENT.md): Kubernetes deployment in detail, including Temporal Cloud
- [ADMIN_WORKER_VERSIONS.md](ADMIN_WORKER_VERSIONS.md): worker version rollout walkthrough
- [Temporal documentation](https://docs.temporal.io/)
