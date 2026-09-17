# Workshop: Safely Move Fulfillment Ownership

Source spec: [spec.md](../../specs/workshop/safe-fulfillment-handoff/spec.md)  
Code solution: [SOLUTION.md](SOLUTION.md)  
Part 2 source spec: [twc-rollout.md](../../specs/workshop/safe-fulfillment-handoff/twc-rollout.md)

## Goal

Move fulfillment ownership from `processing.Order` to `apps.Order` without disrupting in-flight
orders and without using application feature flags in workflow code.

This is a live code-and-rollout workshop. You will keep order traffic running, change the code,
start new worker processes with new build IDs, and then use Temporal Worker Deployment commands to
move traffic. After that, you will see the Temporal Worker Controller automate the same lifecycle
from Kubernetes rollout state.

## Overview

This workshop has two parts:

- **Part 1 — Manual rollout via Temporal CLI** (45 min, hands-on): you change the code, start v3
  workers, and call `set-current-version` / `set-ramping-version` to move traffic. Runs against a
  local Temporal dev server; no Kubernetes required.
- **Part 2 — Automated rollout with the Temporal Worker Controller** (15 min, instructor-led):
  the same code change, deployed to a Kubernetes cluster, with TWC driving the rollout from a
  `WorkerDeployment` manifest. Requires KinD or k3d.

Part 2 reuses the Part 1 v3 code. The pedagogical point of Part 2 is that nothing in workflow code
changes — only the operator surface differs (CLI commands vs. Kubernetes-driven rollout).

The operational rollout order stays the same in both parts:

1. Confirm `apps v2` and `processing v2` are current.
2. Start sustained order traffic.
3. Implement and start `processing v3`.
4. Promote `processing v3` to current.
5. Implement `apps v3`.
6. Start fulfillment-side workers for the new path.
7. Start and ramp or promote `apps v3`.
8. Verify `fulfillment.Order` receives new-path traffic and Kafka handoffs stop for that path.

## Part 1: Manual Rollout via Temporal CLI

### Starting Assumptions And Setup

The only workshop state assumed before this workshop is steps 1 and 2 in
[WORKSHOP.md](../../WORKSHOP.md): you have access to keys and `.env.local` is present in your
Codespace.

Do not assume any local services are already running. Start Temporal, set up namespaces, then start
the explicit service list below.

Run Part 1 from this workshop's directory:

```bash
cd workshop/safe-fulfillment-handoff
```

The `scripts/` directory contains the step runners for this workshop. They start foreground
Java services and Python workers as background processes, write logs under
`.workshop/safe-fulfillment-handoff/logs`, and write PID files under the matching
`run` directory. Temporal CLI commands are shown directly in the steps because they are the
important rollout mechanics.

Start Temporal server in its own terminal and leave it running:

```bash
temporal server start-dev \
  --ip 0.0.0.0 \
  --port 7233 \
  --ui-ip 0.0.0.0 \
  --ui-port 8233
```

Set up namespaces and Nexus endpoints:

```bash
../../scripts/setup-temporal-namespaces.sh
```

### Initial Services

Start only the services needed for baseline order traffic and the enablements load generator:

- `apps-api`
- `apps-workers v2`
- `processing-api`
- `processing-workers v2`
- `enablements-api`
- `enablements-workers`

Do not start fulfillment workers yet. The legacy path should prove that orders are flowing through
`apps v2 -> processing -> Kafka fulfillment` before `apps v3` starts using `fulfillment.Order`.

```bash
./scripts/start-initial-services.sh
```

`start-initial-services.sh` builds Java, starts the six initial services, and waits for readiness.
It does not run Worker Deployment commands.

Useful runtime commands:

```bash
./scripts/status.sh
./scripts/logs.sh apps-workers-v1
./scripts/stop.sh
```

### 1. Confirm `v2` Is Current

Set both deployments to `v2`, then confirm the state:

```bash
temporal worker deployment set-current-version \
  --deployment-name processing \
  --build-id v2 \
  --namespace processing \
  --yes

temporal worker deployment set-current-version \
  --deployment-name apps \
  --build-id v2 \
  --namespace apps \
  --yes
```

```bash
temporal worker deployment describe \
  --name processing \
  --namespace processing

temporal worker deployment describe \
  --name apps \
  --namespace apps
```

Expected result: `processing` and `apps` both show `v2` as current.

### 2. Start Sustained Traffic

Start the enablements load generator and leave it running through the rollout:

```bash
## scripts/start-load.sh 

export ENABLEMENT_ID="safe-handoff-$(date +%Y%m%d%H%M%S)"

temporal workflow start \
  --task-queue enablements \
  --type WorkerVersionEnablement \
  --workflow-id "${ENABLEMENT_ID}" \
  --namespace default \
  --input "{\"enablementId\":\"${ENABLEMENT_ID}\",\"orderCount\":1000,\"submitRatePerMin\":12,\"timeout\":\"900s\",\"orderIdSeed\":\"order\"}" \
  --input-meta 'encoding=json/protobuf'
```

```bash
temporal workflow query \
  --workflow-id "${ENABLEMENT_ID}" \
  --namespace default \
  --type getState
```

Expected result: new `apps.Order` executions appear continuously in the `apps` namespace with
workflow IDs that start with `order-${ENABLEMENT_ID}`.

### 3. Observe The Legacy Path

Pick a generated order ID from Temporal UI in the `apps` namespace:

```bash
export ORDER_ID="<generated-order-id>"
curl -s "http://localhost:8071/admin/order-fulfillment/${ORDER_ID}"
```

Expected result: with `apps v2` and `processing v2`, generated orders create Kafka fulfillment
records.

### 4. Implement `processing v3`

> Only edit the processing proto contract and the **processing** `v2/OrderImpl.java` file in this
> step. The apps context will change shortly.

Apply the **processing** changes from [SOLUTION.md](SOLUTION.md#processing-v3-code). This is a
guided copy/paste change. When that solution section is complete, come back here and continue with
Step 5.

- Add `send_fulfillment` to the
  [processing proto contract](../../proto/acme/processing/domain/v1/workflows.proto).
- Regenerate protobuf outputs with the
  [project-root generate script](../../scripts/generate.sh).
- Guard the legacy Kafka handoff in
  [processing v2/OrderImpl.java](../../java/processing/processing-core/src/main/java/com/acme/processing/workflows/v2/OrderImpl.java).
- Keep the default backward-compatible: absent `send_fulfillment` means `true`.

The solution file shows repo-root paths. Keep this terminal in the workshop directory for the
scripts, but make code edits against the repo-root files it names.

### 5. Start `processing v3`

Build and run a second processing worker process with the same deployment name and a new build ID:

```bash
./scripts/start-processing-v2.sh
```

Confirm `processing v3` is polling:

```bash
temporal worker deployment describe \
  --name processing \
  --namespace processing
```

Do not stop `processing v2`. Existing pinned executions may still need it.

> **Pro Tip**:
> Check out the Temporal UI at `/namespaces/processing/workers/deployments/processing` to see the current status of all Deployments.

### 6. Promote `processing v3`

```bash
temporal worker deployment set-current-version \
  --deployment-name processing \
  --build-id v3 \
  --namespace processing \
  --yes
```

```bash
temporal worker deployment describe \
  --name processing \
  --namespace processing
```

Expected result: new `processing.Order` executions are pinned to `processing v3`.

Why this is safe: `apps v2` still does not set `send_fulfillment`, and `processing v3` treats the
absent field as `true`, so old app traffic still publishes the legacy Kafka handoff.

### 7. Implement `apps v3`

Apply the **apps** changes from [SOLUTION.md](SOLUTION.md#apps-v3-code) in
[apps v2/OrderImpl.java](../../java/apps/apps-core/src/main/java/com/acme/apps/workflows/v2/OrderImpl.java).
The fulfillment wiring helpers are already in the class. The coding activity is calling those
helpers from the workflow path and setting `send_fulfillment=false` on the processing request.
When that solution section is complete, come back here and continue with Step 8:

- Start `fulfillment.Order` through the `Fulfillment` Nexus service.
- Continue calling `processing.Order`.
- Send `send_fulfillment=false` in the processing request.
- After processing succeeds, send `fulfillment.fulfillOrder(...)`.

> **Shortcut:** If you'd rather skip the manual edits, run these two commands from the *repo root*:
> ```bash
> cp java/apps/apps-core/src/main/java/com/acme/apps/workflows/v3/OrderImpl.java \
>    java/apps/apps-core/src/main/java/com/acme/apps/workflows/v2/OrderImpl.java
> sed -i '' 's/^package com.acme.apps.workflows.v3;/package com.acme.apps.workflows.v2;/' \
>    java/apps/apps-core/src/main/java/com/acme/apps/workflows/v2/OrderImpl.java
> ```

### 8. Start Fulfillment Workers For The New Path

Do this after the legacy path is proven and before any `apps v3` worker receives traffic. The
initial service list intentionally left fulfillment stopped so the baseline generator shows
`apps v2 -> processing -> Kafka fulfillment`.

```bash
./scripts/start-fulfillment.sh
```

Expected result: Java fulfillment workers are healthy and the Python worker logs
`python-fulfillment-worker ready` or `All workers polling`.

You do not need to start `fulfillment-api` for this workshop. The new path reaches
`fulfillment.Order` through the `oms-fulfillment-v1` Nexus endpoint.

> **Pro Tip**: 
> Check out the fulfillment Worker running at `namespaces/fulfillment/workers/deployments`in the Temporal UI.

### 9. Start `apps v3`

Build and run a second apps worker process with the same deployment name and a new build ID:

```bash
./scripts/start-apps-v2.sh
```

Confirm `apps v3` is polling:

```bash
temporal worker deployment describe \
  --name apps \
  --namespace apps
```

Do not stop `apps v2`. Existing pinned executions may still need it.

### 10. Move Traffic To `apps v3`

For a visible mixed period, ramp `apps v3` first:

```bash
temporal worker deployment set-ramping-version \
  --deployment-name apps \
  --build-id v3 \
  --percentage 50 \
  --namespace apps \
  --yes
```

OR, If you want a direct cutover instead:

```bash
temporal worker deployment set-current-version \
  --deployment-name apps \
  --build-id v3 \
  --namespace apps \
  --yes
```

Expected result during the ramp: the generator keeps submitting orders; some new `apps.Order`
executions run on `apps v2`, and some run on `apps v3`.

### 11. Inspect Proof

In Temporal UI:

- In the `apps` namespace, inspect recent `apps.Order` executions and note their Deployment
  Version.
- In the `processing` namespace, inspect the `ProcessOrderRequest` input.
- New-path processing inputs should include `options.send_fulfillment=false`.
- In the `fulfillment` namespace, confirm new-path orders create `fulfillment.Order` workflows.

For a new-path order:

```bash
export ORDER_ID="<new-path-order-id>"
curl -s "http://localhost:8071/admin/order-fulfillment/${ORDER_ID}"
```

Expected result: old-path orders have a Kafka record; new-path orders have a `fulfillment.Order`
workflow and no Kafka record.

### 12. Complete The Cutover

```bash
temporal worker deployment set-current-version \
  --deployment-name apps \
  --build-id v3 \
  --namespace apps \
  --yes
```

```bash
temporal worker deployment describe \
  --name processing \
  --namespace processing

temporal worker deployment describe \
  --name apps \
  --namespace apps
```

Expected result: both deployments are current on `v3`; old pinned executions continue on their
original versions until they drain.

Stop the generator when Part 1 is complete:

```bash
./scripts/stop-load.sh
```

Stop the workshop services when you are done with Part 1, or leave them running if you plan to
continue to Part 2's KinD demo on the same machine:

```bash
./scripts/stop.sh
```

### Part 1 Takeaway

Code changes create new worker behavior. Starting a worker with a new build ID makes that behavior
available to Temporal. Worker Deployment commands decide when new workflow executions receive that
behavior.

## Part 2: Automated Rollout with the Temporal Worker Controller

Source material: [java/enablements/ENABLEMENT.md](../../java/enablements/ENABLEMENT.md)

Part 1 had you call `set-current-version` and `set-ramping-version` by hand. Part 2 shows the same
Worker Deployment lifecycle driven by the Temporal Worker Controller (TWC) from a
`WorkerDeployment` manifest in Kubernetes. The application code does not change between
Part 1 and Part 2 — only the operator surface.

This part is instructor-led with `k9s` as the primary view. The commands below are runnable for
self-paced replay.

> **Working directory:** unlike Part 1, run Part 2 commands from the **repo root**. The
> project-root scripts (`scripts/kind/*`, `scripts/k3d/*`) assume that cwd. Workshop-local
> helpers are invoked with their full path,
> e.g. `./workshop/safe-fulfillment-handoff/scripts/apply-twc-processing.sh`.

### How Part 2 maps to Part 1

| Part 1 manual command | Part 2 controller behavior |
|---|---|
| `set-current-version --deployment-name processing --build-id v3` | TWC promotes the new processing Worker Deployment Version after pollers appear |
| `set-ramping-version --deployment-name apps --build-id v3 --percentage 50` | TWC applies progressive rollout steps from the `WorkerDeployment` spec |
| `set-current-version --deployment-name apps --build-id v3` | TWC completes the rollout after each ramp step's pause |
| manually stop old workers | TWC sunsets old versions after configured drain delays |

### Prerequisites

- A local Kubernetes cluster (KinD or k3d) — see [DEPLOYMENT.md](../../docs/DEPLOYMENT.md) for setup
- Temporal Worker Controller v1.7.0 installed in the cluster (Helm chart 0.26.0 + CRDs applied
  separately; see [DEPLOYMENT.md](../../docs/DEPLOYMENT.md))
- The Part 1 `processing v3` code change applied (`send_fulfillment` proto field + guarded Kafka
  handoff). The apps v3 change is optional for Part 2 — Part 2 demonstrates the processing
  rollout.
- `k9s` on PATH for visual observation

### 1. Bring up the Kubernetes Demo Stack

Choose one cluster runner and stay with it:

```bash
OVERLAY=local ./scripts/kind/demo-up.sh
# or
OVERLAY=local ./scripts/k3d/demo-up.sh
```

These project-root scripts (not workshop-local) bring up the full topology: namespaces, configmaps,
secrets, the `WorkerDeployment` resource for `processing-workers`, and the supporting Java
and Python services.

Open `k9s` and stay in the `temporal-oms-processing` namespace:

```text
:ctx kind-temporal-oms
:ns temporal-oms-processing
:pods
```

k9s shows one resource type at a time per process. To watch the controller as well, either
switch the current view (`:wd` for `WorkerDeployment` — the CRD ships the `wd`
short name, so no alias registration is required) or open `k9s` in a second terminal / tmux
pane and run `:wd` there so you can watch pods and controller state side by side:

```text
:wd
```

Use the long form `:workerdeployments` if the short name is not picked up for any
reason (e.g., an older CRD bundle).

Confirm `processing-workers` pods are running with image tag `:v1`.

### 2. Generate Sustained Load

Tunnel from the host into the cluster so a host-side Temporal CLI can talk to in-cluster Temporal
(or to Temporal Cloud through the cluster):

```bash
./scripts/kind/tunnel.sh
# or
./scripts/k3d/tunnel.sh
```

Start the `WorkerVersionEnablement` workflow (same generator as Part 1, lower volume for the
shorter Part 2 timebox):

```bash
export ENABLEMENT_ID="twc-demo-$(date +%Y%m%d%H%M%S)"

temporal workflow start \
  --task-queue enablements \
  --type WorkerVersionEnablement \
  --workflow-id "${ENABLEMENT_ID}" \
  --namespace default \
  --input "{\"enablementId\":\"${ENABLEMENT_ID}\",\"orderCount\":20,\"submitRatePerMin\":5,\"timeout\":\"600s\",\"orderIdSeed\":\"order\"}" \
  --input-meta 'encoding=json/protobuf'
```

Verify in Temporal UI that new `processing.Order` workflows are reporting `DeploymentVersion = v1`.

### 3. Deploy processing v2 and Watch the Controller Drive the Rollout

Build, load, and patch the `WorkerDeployment` to the new image tag (run from repo root):

```bash
./workshop/safe-fulfillment-handoff/scripts/apply-twc-processing.sh
# wraps: VERSION=v2 ./scripts/<runner>/deploy-processing-workers.sh
# override either with: VERSION=v3 RUNNER=k3d ./workshop/safe-fulfillment-handoff/scripts/apply-twc-processing.sh
```

In `k9s`, watch:

- `:pods` — a new `processing-workers` pod comes up alongside the v1 pod instead of replacing it
  abruptly
- `:workerdeployments` — the controller registers the new build ID, waits for pollers,
  then ramps traffic per the `rollout` policy

Confirm from the CLI:

```bash
temporal worker deployment describe \
  --name processing \
  --namespace processing
```

The ramp is driven by `k8s/processing-versioned/base/temporal-worker-deployment.yaml`:

```yaml
rollout:
  strategy: Progressive
  steps:
    - rampPercentage: 50
      pauseDuration: 30s
    - rampPercentage: 90
      pauseDuration: 30s
```

The controller implicitly proceeds to 100% after the final step. In-flight `processing.Order`
executions stay pinned to v1 — exactly the contract Part 1 protected by hand.

### 4. Migrate the Long-Running `support-team` Workflow

`support-team` is intentionally long-lived; with default `pinned` versioning it would keep v1 pods
alive indefinitely.

Move it to `auto_upgrade`:

```bash
temporal workflow update-options \
  --workflow-id "support-team" \
  --versioning-override-behavior auto_upgrade \
  --namespace processing
```

Why `auto_upgrade` is safe here and not for orders: an order workflow must finish on the
fulfillment path it started with (pinned) — that was the entire point of Part 1. `support-team`
has no per-instance fulfillment contract; it can pick up the new code at its next workflow task.

### 5. Watch v1 Pods Sunset

In `k9s` `:pods`, the v1 `processing-workers` pod scales down once no pinned executions remain.
Sunset timing is controlled by the manifest:

```yaml
sunset:
  scaledownDelay: 30s
  deleteDelay: 120s
```

### Part 2 Takeaway

Every manual Worker Deployment command from Part 1 has a controller-driven equivalent. TWC is not
a different architecture — it performs the same Worker Deployment lifecycle from Kubernetes
rollout state, lets the manifest declare ramp policy and sunset timing, and contrasts naturally
with `auto_upgrade` for long-running workflows that have no per-instance versioning contract.

## Combined Takeaway

Code changes create new worker behavior. Temporal Worker Deployments decide which new behavior new
executions receive. In Part 1 you drove that decision interactively with the CLI. In Part 2 the
Temporal Worker Controller drove the same decision from declarative manifests. Either way, in-flight
executions stay on the build they started with, and operators control the rollout — not workflow
code.
