# Hosting and Access: Running Multiple OMS Versions

**Status:** Draft
**Owner:** Temporal FDE Team
**Created:** 2026-09-16
**Updated:** 2026-09-17
**Depends on:** [`spec.md`](spec.md) for the component/OMS version numbering this document pins to

## Purpose

`spec.md` makes every `apps.Order`, `processing.Order`, and fulfillment version real, buildable code
and defines OMS version as a named combination of the three. Its own Phase 4 only covers running one
OMS version at a time, sequentially, through `demo-up.sh`. This document covers the separate concern
of *hosting, running, and accessing* several versions, as two complementary demo modes with different
resource profiles:

- **Mode A: All Versions Concurrent.** Every version of every bounded context runs at once; a
  request (for example two browser tabs) picks which combination handles it. Highest resource cost,
  best for exploring arbitrary and even unsafe combinations side by side.
- **Mode B: Sequential On-Demand Rollout.** Start sustained order load, then trigger a live
  Kubernetes deployment that promotes one bounded context forward while load keeps running, watch it
  roll out, then promote again. Only ever 1-2 versions of a bounded context live at once, the
  resource-efficient complement to Mode A.

## Current State

- Every order-start point (`CommerceWebhookController.submitCommerceOrder`,
  `PaymentsWebhookController.capturePayment`, `ProcessingImpl.processOrderAsync`,
  `FulfillmentImpl`'s Nexus handler) builds a plain `WorkflowOptions`/starts a workflow with no
  version selection at all. Whichever code is currently deployed handles every request.
- `apps` and `fulfillment` each run as one plain Kubernetes `Deployment`. `processing` runs through
  the Temporal Worker Controller's `WorkerDeployment` CRD, which manages a rollout from one current
  version to one ramping version, not an arbitrary number of versions held open indefinitely.
- The web UI has no version concept anywhere, and no route exposes which build-id is currently
  serving a given task queue.
- `java/enablements/enablements-core/.../workflows/WorkerVersionEnablementImpl.java` and
  `.../activities/DeploymentActivitiesImpl.java` already contain a partial, disconnected attempt at
  on-demand deployment (see Mode B below): registered with the worker but not reachable from any REST
  endpoint or UI today, and its deployment activity throws on the missing manifest template it
  depends on.

## Mode A: All Versions Concurrent

### Goal

Given a request, let the caller (web UI or script) choose:
- **Shorthand:** an OMS version (`v1` through the latest defined in `spec.md`), resolved to the
  matching component version for each bounded context.
- **Override:** an explicit component version per bounded context, independent of any OMS pairing,
  including a pairing `spec.md` calls unsafe. Showing *why* an unsafe pairing breaks is a legitimate
  demo goal, not something to prevent at this layer.

### Version Selection Model

Add a `VersionSelection` message to the order request chain:

```protobuf
message VersionSelection {
  optional string oms_version = 1;          // shorthand, e.g. "v4"
  optional string apps_version = 2;         // override
  optional string processing_version = 3;   // override
  optional string fulfillment_version = 4;  // override
}
```

Resolution rule: any override field wins for its bounded context; an absent override falls back to
the `oms_version` shorthand's mapping from `spec.md`'s table; if neither is set, fall back to
whatever's deployed as default (today's behavior, unchanged).

### Threading the Selection End-to-End

Temporal's client-side pin (`WorkflowOptions.setVersioningOverride(new
VersioningOverride.PinnedVersioningOverride(new WorkerDeploymentVersion(deploymentName, buildId)))`,
available in the pinned SDK, `io.temporal:temporal-sdk:1.38.0`) only applies at the point a workflow
is *started*. A Nexus caller cannot tell the callee which version to run on: `NexusServiceOptions`
and `NexusOperationOptions` have no versioning field, confirmed against the SDK sources. So the
selection has to travel as request data, the same way `send_fulfillment` already does, and each
bounded context applies its own pin where it starts its own workflow:

1. `CreateCommerceOrderRequest` (web to `enablements-api`) gains an optional `VersionSelection`.
2. `CommerceOrder` (enablements) carries it through to the webhook payload it sends to `apps-api`.
3. `CommerceWebhookController.submitCommerceOrder` / `PaymentsWebhookController.capturePayment`
   resolve the apps component version and set `WorkflowOptions.setVersioningOverride(...)` on their
   `apps.Order` start (`java/apps/apps-api/.../controllers/CommerceWebhookController.java:126-147`
   and the equivalent in `PaymentsWebhookController.java`).
4. `ProcessOrderRequestExecutionOptions` (the existing routing slip) gains the resolved processing
   and fulfillment component versions.
5. `ProcessingImpl.processOrderAsync()`
   (`java/processing/processing-core/.../services/ProcessingImpl.java:49-73`) reads the processing
   selection and applies its own `setVersioningOverride` before `tcli.newWorkflowStub(...)`.
6. `FulfillmentImpl`'s Nexus operation handler
   (`java/fulfillment/fulfillment-core/.../services/FulfillmentImpl.java`) does the same for the
   fulfillment selection.

### Kubernetes Topology

Task-queue polling happens at the SDK level, not the Kubernetes networking level, so multiple worker
processes for the same bounded context can poll the same task queue concurrently without any
networking change, as long as each one carries a distinct build-id.

- **`apps` and `fulfillment`:** add one plain `Deployment` per component version (`apps-worker-v1`,
  `apps-worker-v2`, `apps-worker-v3`, and the fulfillment equivalents), each with its own
  `TEMPORAL_WORKER_BUILD_ID`/workflow-class env and image tag. Each needs a unique
  `metadata.name`/`spec.selector.matchLabels` to avoid collisions, and
  `k8s/overlays/local`'s existing name-targeted JSON patches need one copy per new Deployment name.
- **`processing`:** its `WorkerDeployment` CRD (`k8s/processing-versioned/`) stays exactly as it is,
  reserved for the existing progressive-rollout demo (`twc-rollout.md`), since Temporal's versioning
  model caps that mechanism at one current plus one ramping version, not an arbitrary held-open set.
  For this mode, deploy `processing` the same plain-`Deployment`-per-version way as `apps` and
  `fulfillment`, as a second, separate topology.
- Put this whole topology behind an opt-in flag (e.g. `OMS_EXPLORE=1` on `app-deploy.sh`, or a
  dedicated `k8s/overlays/local-explorer`), never the `demo-up.sh` default: running every version at
  once is roughly 9 worker pods (apps 3, processing 4, fulfillment 2) versus 3 today, which is a
  meaningful jump on a resource-constrained local cluster.

### Web UI

- A version selector on the shop/checkout flow: an "OMS version" dropdown, plus an "advanced:
  override per bounded context" control. Populate both from one source of truth (a small endpoint,
  or generated static config) built from `spec.md`'s OMS version table, not a second hand-maintained
  copy of it.
- Persist the selection as a URL query parameter (e.g. `?omsVersion=v4`), not just in-memory
  component state, so opening two browser tabs with different values genuinely runs two versions
  side by side without any shared session state.
- Extend the existing order-tracking page (`shop/orders`) to show which build-id actually handled
  each step, so a demo can prove two tabs ran different code, not just that they were asked to.

### Scripts

- A script (or `app-deploy.sh` flag) that brings up the explorer topology from Kubernetes Topology
  above.
- Confirm whether `temporal workflow start` exposes a CLI flag for a start-time pinned versioning
  override, or whether that capability is Java-SDK-only today. If the CLI can't do it, a scripted
  demo should call the same webhook/API entry points the web UI uses, rather than a raw `temporal
  workflow start`, so the UI and scripts share one code path for applying a pin.

### Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Running every version simultaneously exhausts local cluster resources | Demo fails to come up | Opt-in flag; document expected pod count and memory delta; allow running a subset of versions |
| `temporal` CLI has no start-time pin flag | Scripted demos can't bypass the web UI | Route scripts through the same API entry points as the UI instead of the raw CLI |
| Six new hand-off points for a routing-slip field is easy to leave partially wired | A pin silently doesn't apply somewhere in the chain | Cover the full chain (steps 1-6 above) with one integration test per bounded context boundary |

### Open Questions

- [ ] Confirm the `VersionSelection` proto shape and field names with whoever owns the commerce/order
      protos before wiring it through five files.
- [ ] Confirm whether `temporal workflow start` supports a start-time pinned versioning override.
- [ ] Decide the exact opt-in mechanism for the explorer topology (`app-deploy.sh` flag vs. a
      dedicated overlay) before scripting it.
- [ ] Decide whether the web UI's "advanced override" control ships in the first pass or as a
      follow-up once the OMS-version shorthand path works end-to-end.

### Success Criteria

- Two requests, submitted concurrently with different `VersionSelection` values, are each handled by
  the worker pod matching their selection, confirmed via `temporal workflow describe` showing the
  expected `Deployment Version` on each execution.
- Two browser tabs with different `?omsVersion=` values complete their orders through visibly
  different fulfillment paths (Kafka record vs. `fulfillment.Order` workflow), matching `spec.md`'s
  OMS version table.

## Mode B: Sequential On-Demand Rollout

### Goal

Start sustained order load (optionally, from a baseline version, though load may also be off, or
started later than a deployment), then use the web UI to promote one bounded context to its next
version while load keeps running, watch the rollout progress and the already-running load's status,
and repeat for the next version. Load and deployment are two independent concerns that may happen at
different times; a deployment must work with zero load running, and starting load must not require a
deployment. This is the resource-efficient complement to Mode A: only 1-2 versions of a bounded
context are ever live for a given demo run.

### What Already Exists (and what's broken)

`java/enablements/enablements-core/src/main/java/com/acme/enablements` has most of the underlying
pieces, disconnected from any endpoint or UI, and partly broken:

- `workflows/WorkerVersionEnablementImpl.java` runs a load-submission loop and a `deployWorkerVersion`
  signal together in one workflow, switching a `DemoPhase` state machine
  (`RUNNING_V1_ONLY -> TRANSITIONING_TO_V2 -> RUNNING_BOTH -> COMPLETE`) as deploys land. This couples
  load and deploy into one lifecycle, which this design deliberately avoids (see Design below); it is
  not the near-term reuse target, only a documented future option once combined orchestration is
  wanted.
- `activities/DeploymentActivitiesImpl.java`'s `deployWorkerVersion()` shells out to
  `kubectl apply -f <tempfile>` against a manifest template at
  `enablements.deployment.manifest-template`
  (`k8s/base/processing/processing-workers-deployment-template.yaml`). That file does not exist, so
  this throws today.
- Its `registerCompatibility()` shells out to the deprecated `temporal worker-build-id
  update-compatibility` CLI subcommand, hardcoded to `v1`/`v2`, ignoring the actual request. This
  matches `SPECS/enablements/worker-versioning/spec.md`, a stale, unapproved 2026-03-18 draft written
  against Temporal's old build-id-compatibility API, before this repo standardized on the modern
  Worker Deployment model (`k8s/processing-versioned`, `temporal worker deployment
  set-current-version`). That spec is superseded by this design and should not be built against
  further.
- `DeployWorkerVersionRequest` (proto) has `deployment_name`, `build_id`, `version`, and optional
  `replica_count`. No ramp-percentage field.

### Resolved Decisions

- **`SPECS/enablements/worker-versioning/spec.md` is formally superseded by this document.**
  Reconciled against it directly so nothing of value is lost:
  - Its core goal (deploy a new build-id alongside the old one, shift new traffic, let old traffic
    drain) is what this design already does, on the modern Worker Deployment API instead of the
    deprecated build-id-compatibility API it was written against.
  - Its "automatic rollback: mark v2 incompatible" idea carries forward as a first-class capability
    here, not lost: rolling back is calling the same `POST /api/v1/enablements/deployments/{boundedContext}`
    endpoint with an *older* build-id. No separate rollback mechanism is needed because promotion and
    rollback are the same operation in this design.
  - Its "canary via reduced replica count" idea is superseded by the modern API's ramp-percentage
    mechanism, which is more precise (traffic-percentage, not pod-count) but deliberately deferred
    here (see below); noted so it isn't reinvented from scratch later.
  - Its concrete version-transition test (submit orders on the old version, promote, submit more,
    verify old orders stayed on the old version and new orders landed on the new one, both complete)
    is carried into this document's Success Criteria below, not dropped.
  - Its "runbook" deliverable is superseded by this document plus the admin UI itself: an operator
    doesn't need a separate written runbook when the same steps are buttons with live state.
  - Mark its `Status` as `Superseded` and add a one-line pointer to this document; its content stays
    as historical reference, not deleted.
- **No ramp-percentage field on `DeployWorkerVersionRequest` for this pass.** A direct cutover
  (`set-current-version`, no ramp) is enough. Ramping remains a real, understood future extension
  (the modern equivalent of the old spec's canary idea), not something this design forecloses, just
  not built now.
- **This lives under a new "Admin" area of the site, not the existing shop demo.** The load-gen panel
  at `demo/+page.svelte` is part of the shop-facing demo; this is an operator capability (deploy a
  version, watch it roll out, jump to Temporal UI), a different audience. Give it its own route,
  e.g. `web/src/routes/admin/worker-versions/+page.svelte`, reusing the *existing* load-gen REST
  endpoints (`/api/v1/enablements/worker-version/{start,stop,{id}}`) from this new page rather than
  either duplicating them or bolting deploy controls onto the shop demo page.

### Design

Load and deployment stay decoupled, each exposed the same way: as a standalone activity invocation,
no owning workflow.

- **Load: unchanged, surfaced in Admin.** Keep the existing standalone-activity path exactly as it
  is: `OrderActivities.runSubmissionLoop`, invoked directly via `LoadGeneratorService` /
  `EnablementsController`'s existing `/api/v1/enablements/worker-version/{start,stop,{id}}`
  endpoints. The new Admin page adds a start/stop control wired to these existing endpoints; it does
  not change or duplicate them. A deployment action never depends on load being on, and load never
  depends on a deployment having happened.
- **Deploy: fix and expose independently.** `DeploymentActivitiesImpl` holds the real, reusable
  logic; fix it and call it as a standalone activity the same way `LoadGeneratorService` fires
  `runSubmissionLoop`, through a new endpoint, e.g.
  `POST /api/v1/enablements/deployments/{boundedContext}` taking a target version:
  - Add the missing manifest template(s), generalized for `apps`/`processing`/`fulfillment` (not just
    `processing`), consistent with `spec.md`'s package-per-version build-id/image-tag convention.
  - Replace `registerCompatibility()`'s deprecated CLI call with the modern `temporal worker
    deployment set-current-version --deployment-name <name> --build-id <id> --namespace <ns> --yes`
    (matching `workshop/safe-fulfillment-handoff/scripts/_lib.sh`'s `set_current_version`), using the
    request's real deployment name/build id instead of the hardcoded v1/v2.
  - After calling `set-current-version`, call `temporal worker deployment describe` and surface its
    result in the response, so a silent failure to actually promote (the old `registerCompatibility()`
    bug) can't happen unnoticed again.
- **Admin page: two independent panels, not one combined state.** Load status/controls (existing,
  reused as-is) and deployment status/controls per bounded context (new: current build-id, last
  deploy action/result). Both link out to the Temporal UI (`PUBLIC_TEMPORAL_UI_BASE_URL`, already
  used elsewhere in the web app) for the relevant namespace/task-queue/deployment-version, since
  Temporal's own UI already shows which execution ran on which version; this page doesn't need to
  re-implement that mapping itself.
- **`processing`** can keep using the existing `k8s/processing-versioned` `WorkerDeployment` CRD as
  is (built for exactly this one-current-plus-one-ramping transition shape). For `apps`/`fulfillment`
  (plain `Deployment`s), promoting means: deploy the new version, wait for pollers, call
  `set-current-version`, then scale down/remove the prior version's Deployment, so old versions don't
  pile up the way Mode A's topology deliberately keeps them all running.

### Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| `DeploymentActivitiesImpl`'s missing manifest template silently breaks every environment that references it | Deploy action throws at runtime | Add the template as part of the same change that wires the new endpoint, with a test that renders it |
| Reusing the deprecated `registerCompatibility()` call as-is would keep shipping a broken mechanism | Version promotion appears to succeed but never actually routes traffic | Replace it with `set-current-version` in the same change, not as a follow-up, and verify via `describe` |
| Coupling load and deploy later without a clear boundary re-introduces the coupling this design avoids | Deploy actions accidentally depend on load state again | Keep the two REST endpoints and their activities fully independent; only combine them behind an explicit future opt-in |

### Success Criteria

- A deployment action succeeds with zero load running, and load can be started/stopped with no
  deployment ever having happened, proving the two are independent.
- Clicking "deploy to OMS v2" (or an equivalent per-context promotion) for a bounded context results
  in `temporal worker deployment describe` showing the new build-id as current, and the Admin page's
  Temporal UI link lands on that same state.
- Version-transition test carried over from the superseded spec: submit orders on the old version,
  promote, submit more orders, and confirm the old orders stayed on the old build-id, the new orders
  landed on the new build-id, and all of them complete successfully.
- Rolling back (deploying an older build-id after a newer one is current) succeeds through the same
  endpoint with no special-casing.

## Explicitly Out of Scope

- Any change to the existing TWC progressive-rollout demo (`k8s/processing-versioned`,
  `twc-rollout.md`); both modes here are additive, not replacements.
- Re-litigating `spec.md`'s own package restructuring; this document assumes that numbering and
  layout, it doesn't redo it.
- Combined load-and-deploy orchestration in one workflow (`WorkerVersionEnablementImpl`'s current
  shape); noted as a future option under Mode B, not built now.
