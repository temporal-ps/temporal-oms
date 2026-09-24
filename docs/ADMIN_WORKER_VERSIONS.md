# Admin UI: Worker Version Rollout

Walkthrough for the Admin UI at `/admin/worker-versions`: promote apps, processing, and
fulfillment to a target OMS version with one click (hosting.md
[`OMS-Version-Driven Promotion`](../specs/oms-evolution/hosting.md)), or promote one
bounded context at a time via the Advanced controls (hosting.md Mode B).

Works identically on KinD or k3d — the two script directories are parallel and expose the
same ports. Examples below use `kind`; substitute `k3d` throughout for the other runner.

---

## Prerequisites

**Level 2 or Level 3 already running** (see the root [README.md](../README.md)):

```bash
./scripts/setup-temporal-namespaces.sh
./scripts/kind/infra-up.sh
./scripts/kind/app-deploy.sh
```

**To start genuinely at OMS v1 (no Worker Versioning anywhere)**, set
`SKIP_VERSION_REGISTRATION=1` on the setup script instead - registering `build-id=local` as
current is a one-way door (Temporal has no API to unset a Worker Deployment's current version,
only to change it), so it has to be skipped from the very first bring-up:

```bash
SKIP_VERSION_REGISTRATION=1 ./scripts/setup-temporal-namespaces.sh
```

This only affects `apps`/`fulfillment`. `processing` still starts versioned regardless (its
Worker Versioning is engaged by the Temporal Worker Controller the moment its CRD comes up, not
by this script) - promoting it to OMS v1 later still works via the Admin UI, this only changes
what state the cluster starts in.

**Don't place any orders before your first Admin UI promotion.** The raw, freshly-deployed
`apps-worker`/`fulfillment-workers` pods poll successfully but receive zero tasks until some
version is explicitly made current - promote to a target OMS version (v1 included) first, then
start load or place orders.

If you deployed before this feature existed, redeploy so `enablements-workers` picks up
`OmsVersionRolloutImpl`:

```bash
./scripts/kind/app-deploy.sh
```

Verify pods are healthy:

```bash
./scripts/kind/status.sh
```

---

## Step 1: Open the Admin UI

```bash
./scripts/kind/tunnel.sh   # run in another terminal; leave it running
```

Open **http://localhost:3000/admin/worker-versions**. The page's `/api/v1/enablements/*`
calls are routed by Traefik straight to `enablements-api` on the same port — no separate
API tunnel needed for this page.

---

## Step 2 (optional): Start load

Click **Start load** in the Load panel to submit continuous orders while you roll out
versions. Not required — a deployment action works with zero load running, and load works
with no deployment ever having happened.

---

## Step 3: Promote by OMS version

In the **OMS Version** panel, pick a target version from the dropdown and click
**Start rollout**. The dropdown is populated from `OmsVersionCatalog`
(`specs/oms-evolution/spec.md`'s table): OMS v1 (baseline, no Worker Versioning) through
v4 (apps owns fulfillment via Nexus) are implemented and safe to select; v5/v6 are listed
but "future" per the spec — the code for their component versions exists, but they haven't
been exercised end-to-end.

What happens on click:

1. A new `OmsVersionRollout` workflow starts on the `enablements` task queue and reads
   apps' current build id from `temporal worker deployment describe`.
2. It promotes the three bounded contexts in the order that avoids an unsafe intermediate
   pairing: rolling forward, processing and fulfillment go first and apps goes last;
   rolling back, apps goes first. See hosting.md for the full rule.
3. If the target's fulfillment column is `embedded` (OMS v1–v3), the fulfillment step is
   marked **SKIPPED**, not attempted — there's no standalone fulfillment component to
   deploy yet at those versions.
4. The progress panel polls the workflow every 2s and shows PENDING → IN_PROGRESS →
   SUCCEEDED/FAILED/SKIPPED per step, each linking to the Temporal UI's Worker Deployment
   page for that bounded context.

Confirm from the CLI:

```bash
export KUBECONFIG=/tmp/kind-config.yaml
temporal worker deployment describe --name apps --namespace apps
temporal worker deployment describe --name processing --namespace processing
temporal worker deployment describe --name fulfillment --namespace fulfillment
```

**A step failing stops the rollout.** It does not attempt the remaining contexts, since
continuing risks landing on an unsafe combination. Recover manually via the Advanced
controls below, then retry the OMS version rollout.

---

## Step 4: Advanced — promote one bounded context, or force the unsafe pairing

The **Advanced** section below the OMS Version panel is the original per-context control:
pick a bounded context, type a target version (e.g. `v3`), click **Promote**. Use it to:

- Recover after a failed OMS-version rollout step.
- Deliberately demonstrate the documented unsafe pairing — promote `apps` to `v3` while
  `processing` is still on `v1` or `v2` — and watch orders double-publish to both Kafka and
  `fulfillment.Order` (`temporal workflow list --namespace fulfillment` alongside your
  Kafka consumer/topic of choice). The OMS Version panel above refuses to construct this
  combination on its own; Advanced is the only way to force it on purpose.

---

## The `local` baseline gets cleaned up automatically

Before your first promotion, `apps` and `fulfillment` run their base Deployments
(`apps-worker`, `fulfillment-workers`, image tag `:latest`) registered under build-id
`local` — that's `acme.apps.yaml`/`acme.fulfillment.yaml`'s default
(`build-id: ${TEMPORAL_WORKER_BUILD_ID:local}`) when `app-deploy.sh` doesn't set the
`TEMPORAL_WORKER_BUILD_ID` env var. `k8s/base/apps/deployment-workers.yaml` and
`k8s/base/fulfillment/deployment-workers.yaml` label these Deployments
`bounded-context: apps`/`fulfillment`, `oms-build-id: "local"` — the same labels the
versioned template (`k8s/base/templates/worker-deployment-template.yaml`) applies to the
Deployments a promotion creates. That means the very first real promotion's cleanup step
(`DeploymentActivitiesImpl.removeStaleVersions`, `kubectl delete deployment -l
bounded-context=<context>,oms-build-id!=<newBuildId>`) matches and removes the `local`
baseline Deployment too, exactly like it removes any other stale version. No manual
cleanup needed.

**One caveat:** re-running `app-deploy.sh` after a promotion re-applies the kustomize base,
which recreates the `local`-labeled `apps-worker`/`fulfillment-workers` Deployment even
though a real build-id is already current. It sits idle (Temporal only routes to the
current build-id) until your next promotion's cleanup removes it again — harmless, but
don't be surprised to see it reappear after a redeploy.

---

## Cleanup

```bash
./scripts/kind/demo-down.sh
```
