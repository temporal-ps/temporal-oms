# Hosting and Access: Running Multiple OMS Versions

**Status:** Draft
**Owner:** Temporal FDE Team
**Created:** 2026-09-16
**Updated:** 2026-09-22
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
  - Both calls above are since superseded by native SDK clients, not CLI subprocesses - see
    "Deploy and Version Clients" below.
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

### OMS-Version-Driven Promotion

The per-bounded-context promotion above (`POST /api/v1/enablements/deployments/{boundedContext}`)
requires the operator to already know the correct apps/processing/fulfillment version for a target
OMS version and to click three independent buttons in the right order. This adds a single-input
alternative: pick an OMS version (`spec.md`'s table), and a new orchestrating workflow promotes the
three bounded contexts safely, reusing `deployWorkerVersion` as-is.

**Catalog, single source of truth:** `OmsVersionCatalog` (new, `enablements-core`) mirrors `spec.md`'s
OMS version table as data, not prose. Exposed read-only via `GET /api/v1/enablements/oms-versions`
(new `OmsVersionRow`/`ListOmsVersionsResponse` proto messages), so the web UI populates its dropdown
from one source instead of a hand-maintained TypeScript copy, per this document's own Mode A guidance
for populating its OMS-version dropdown. All rows the catalog returns are exposed, including the
"future" v5/v6 rows from `spec.md` - the underlying component code for every row already exists
(see this document's Implementation Status), so there is no technical reason to special-case them
out of the operator-facing dropdown.

**Orchestration: a real, new workflow, not a client-side loop or `WorkerVersionEnablementImpl` reuse.**
`OmsVersionRolloutImpl` (new, alongside `WorkerVersionEnablementImpl`) calls `DeploymentActivities
.deployWorkerVersion` up to 3 times per rollout, one per bounded context, with a bounded retry policy
(a handful of attempts, not the SDK default's unlimited backoff - a bad image tag should surface as a
failed rollout, not hang). `WorkerVersionEnablementImpl` is deliberately not reused (per this
document's own Design section, it couples load and deploy); this is a second, independent workflow
that touches only deployment activities. It does not change or depend on load generation at all.

**Fulfillment's `embedded` state is not deployable.** `DeploymentActivitiesImpl.BOUNDED_CONTEXTS` has
no `"embedded"` entry and no such workflow-class package exists (fulfillment has no independent
identity until v1). When the target OMS version's fulfillment column is `embedded`, the workflow
skips the fulfillment step entirely (reports it as `SKIPPED`, not attempted) and leaves whatever
fulfillment deployment currently exists running idle, rather than erroring or tearing anything down -
acceptable indefinitely for a demo app; no teardown mechanism is built for this.

**Rollout ordering: direction-aware around apps, not a fixed sequence.** apps is the only component
whose version change affects whether it depends on processing's `send_fulfillment` support and
fulfillment's existence; a fixed processing→fulfillment→apps order is *not* safe in both directions
(verified: rolling back OMS v4→v1 with a fixed forward-safe order transiently produces apps v3 +
processing v1, which is exactly as unsafe as the documented apps v3 + processing v2 pairing - the
same "processing doesn't understand `send_fulfillment`, always-Kafka" mechanism, generalized. This
extends the spec's one named unsafe pairing to any apps v3 + processing {v1, v2} combination.)

Rule, applied once per rollout using the *current* apps version (queried live, not client-supplied):
- Target apps version > current: promote processing and fulfillment to their targets first, apps last.
- Target apps version < current: promote apps first, then processing/fulfillment.
- Target apps version unchanged: processing/fulfillment order doesn't matter.

Requires one new small activity to read the current apps build id. See
"Deploy and Version Clients" below for how that's read (a native SDK call, not CLI output parsing).

**Reuse, no new deploy activity signature.** The rollout workflow calls the existing
`DeploymentActivities.deployWorkerVersion(DeployWorkerVersionRequest)` unchanged for each context step
- it's already idempotent-safe (create-or-replace the Deployment/patch, then set-current-version, both
safe to repeat). It also already does the right per-context k8s action on its own: for
`apps`/`fulfillment` it applies a new versioned `Deployment` and removes the stale one; for
`processing` it patches the existing `WorkerDeployment` CRD in place. The rollout workflow does not
need any k8s logic of its own.

That stale-version cleanup only works because `k8s/base/apps/deployment-workers.yaml` and
`k8s/base/fulfillment/deployment-workers.yaml` carry the same `bounded-context`/`oms-build-id: "local"`
labels the versioned template applies - without them, the very first real promotion for a context
would leave that base Deployment running forever as an orphan, since kustomize's own resources are
invisible to `removeStaleVersions`' label selector otherwise. Full rationale in
[`docs/ADMIN_WORKER_VERSIONS.md`](../../docs/ADMIN_WORKER_VERSIONS.md#the-local-baseline-gets-cleaned-up-automatically).

**Admin UI: coexists with, does not replace, the three per-context rows.** Add an OMS-version
dropdown (sourced from `GET /api/v1/enablements/oms-versions`) and a "Start rollout" action above the
existing per-context section, which is relabeled "Advanced: promote a single bounded context" and
kept as-is - it remains the escape hatch for demonstrating the unsafe pairing on purpose, and for
manual recovery if a rollout step fails partway. A rollout in progress shows one row per bounded
context (PENDING/IN_PROGRESS/SUCCEEDED/FAILED/SKIPPED), each linking to the Temporal UI via the
existing `temporalWorkerDeploymentUrl` helper, polled the same way the existing load panel polls
`LoadGenerationState`.

**Partial failure: fail-fast, no auto-continue.** If a step fails after its retries are exhausted, the
workflow stops; it does not attempt the remaining contexts, since continuing past a failed step risks
landing in an unsafe intermediate combo. The Admin UI surfaces which step failed; the operator
recovers via the per-context Advanced controls. A workflow-level "retry from failed step" affordance
is a reasonable future extension, not built in this pass.

#### Open Questions

- [x] Expose OMS v5/v6 in the dropdown now, even though `spec.md` labels them "future" - resolved:
      expose all rows, no special-casing.
- [x] Should a failed rollout step be retryable from where it stopped (workflow signal) in this pass? -
      resolved: no, fall back to the Advanced per-context controls for v1.
- [x] Does rolling back fulfillment from v1/v2 to `embedded` ever need an explicit teardown action? -
      resolved: no, leave it running idle indefinitely.
- [x] Exact JSON shape of `temporal worker deployment describe --output json`'s current-version field
      (needed for the ordering lookup) - moot: superseded by the native SDK response in "Deploy and
      Version Clients" below, which returns the typed `WorkerDeploymentInfo` message directly, no
      CLI output or JSON parsing involved.

#### Success Criteria (in addition to existing Mode B criteria)

- Selecting OMS v4 from a clean OMS v1 state promotes all three contexts in the derived safe order,
  confirmed via `temporal worker deployment describe` on each, with the fulfillment step reported as
  a real promotion (not skipped, since v1→v4 crosses the `embedded`→v1 boundary).
- Selecting OMS v1 from OMS v4 promotes apps back first, then processing/fulfillment, with no
  intermediate `temporal worker deployment describe` observation ever showing apps v3 paired with
  processing v1 or v2.
- A forced failure on the processing step (e.g. bad build id) leaves apps promoted, processing/
  fulfillment untouched, and the Admin UI clearly shows which step failed - no silent partial state.

### Deploy and Version Clients: Kubernetes and Temporal Java SDKs, Not CLI Subprocesses

`DeploymentActivitiesImpl` as built (Design, above) shells out to the `kubectl` and `temporal`
binaries via `ProcessBuilder`. Running the `OmsVersionRolloutImpl` workflow against a real cluster
surfaced two problems with that: the `enablements-workers` container image (`eclipse-temurin:21-jre-
alpine`) has neither binary installed, so every call fails with `Cannot run program "kubectl": ...
No such file or directory`; and even with the binaries present, no RBAC in this repo grants the
`enablements-workers` pod's ServiceAccount permission to touch `Deployment` or
`workerdeployments.temporal.io` resources in the `apps`/`processing`/`fulfillment` namespaces - that
failure would be next.

**Decision: replace both subprocess calls with native Java clients**, not with a Dockerfile fix that
bakes the CLI binaries into the image:

- **Kubernetes operations** (`applyVersionedDeployment`, `patchWorkerDeploymentCrd`,
  `removeStaleVersions`) move to `io.kubernetes:client-java`'s typed `AppsV1Api` (Deployments) and
  `CustomObjectsApi` (the `workerdeployments.temporal.io` CRD), using in-cluster config
  (`ClientBuilder.cluster()`, reading the pod's own ServiceAccount token) rather than a kubeconfig
  file.
- **Temporal Worker Deployment operations** (`setCurrentVersion`, `describeDeployment`) move to the
  Temporal Java SDK's own gRPC stub: `WorkflowServiceStubs.blockingStub().setWorkerDeploymentCurrentVersion(...)`
  and `.describeWorkerDeployment(...)` (`io.temporal.api.workflowservice.v1.WorkflowServiceGrpc`).
  Confirmed present in this repo's pinned SDK version (`temporal-serviceclient:1.38.0`) by inspecting
  the jar directly - no CLI needed for either operation. `currentBuildId` reads
  `DescribeWorkerDeploymentResponse.getWorkerDeploymentInfo().getRoutingConfig()
  .getCurrentDeploymentVersion().getBuildId()` directly off the typed response; no JSON parsing.
- **No `kubectl`/`temporal` CLI in the `enablements-workers` image.** This removes a class of
  fragility this design had not accounted for (a missing binary, and CLI output whose exact JSON
  shape had to be inferred rather than read as a typed field) rather than papering over it.

**The manifest template has the same "not actually in the image" problem, fixed the same way
this repo already ships every other piece of pod config.** `enablements.deployment.manifest-
template`'s default (`k8s/base/templates/worker-deployment-template.yaml`) is a path relative to
the repo root - true when running locally via `LocalEnablementRunner`, never true inside the
`enablements-workers` container, whose Docker build context (`java/enablements/enablements-
workers`) never contained `k8s/` at all, `kubectl`-subprocess era or not. Fixed by shipping the
template as a `configMapGenerator`-built ConfigMap (`worker-deployment-template`, `k8s/base/
kustomization.yaml`), mounted read-only at `/etc/config/k8s-templates/` on `enablements-workers`
(`k8s/base/enablements/deployment-workers.yaml`), with `ENABLEMENTS_DEPLOYMENT_MANIFEST_TEMPLATE`
overriding the property to that mounted path - the same ConfigMap-mount pattern this Deployment
already uses for its Temporal connection config, not a new mechanism.

**The versioned Deployment template's `temporal-secret` volume has the same "assumes something
that only exists for Cloud" problem, hit live the first time a promotion actually created a pod.**
`k8s/base/templates/worker-deployment-template.yaml` mounts a per-context Secret
(`temporal-{context}-api-key`) that only exists under the `cloud` overlay; `local`'s kustomize
patch strips that volume from the *static* `apps-worker`/`fulfillment-workers` Deployments for
exactly this reason, but that patch can never reach a Deployment `DeploymentActivitiesImpl` creates
programmatically at runtime (`apps-worker-v1`, etc.) - confirmed live: `apps-worker-v1` sat in
`ContainerCreating` on `MountVolume.SetUp failed ... secret "temporal-apps-api-key" not found`.
Fixed with `optional: true` on that volume in the template - degrades to an empty mount when the
Secret is absent (local), still populates normally when present (cloud); no overlay-specific
branching needed since it's one shared template.

**Calling Worker Deployment management APIs from Temporal Cloud will very likely need a different,
more privileged credential than the one this code currently uses - not yet hit live, since testing
has been local/OSS only.** `DeploymentActivitiesImpl`'s `setWorkerDeploymentCurrentVersion`/
`describeWorkerDeployment` calls ride on `workflowClient.getWorkflowServiceStubs()` - the
`enablements` namespace's own connection/API key. Locally this is a non-issue: an empty API key and
`TEMPORAL_TLS_ENABLED=false` mean no auth interceptor is configured at all, so these calls ride the
exact same unauthenticated channel as every other Temporal SDK call already working against the OSS
dev server. Against Cloud, per this repo's own Level 3 setup (`README.md`'s service-account table),
Worker Deployment management already needs the elevated `acme-automations-service-account`
(Developer-or-Admin), separate from each bounded context's own Developer-scoped key - that's
explicitly why the Temporal Worker Controller gets its own dedicated account. The `enablements`
namespace's connection is presumably Developer-scoped, not the automations account, so the first
Cloud run of this feature will likely fail on authorization, not confirm/timeout like the local
issues above.

**RBAC, added:** `k8s/base/enablements/rbac.yaml` adds a dedicated `enablements-workers`
ServiceAccount (bound to the Deployment via `spec.template.spec.serviceAccountName`), a
`ClusterRole` (`enablements-worker-deployments`) granting `get`/`list`/`watch`/`create`/`update`/
`patch`/`delete`/`deletecollection` on `deployments` (apps) and `services` (core), and
`get`/`list`/`watch`/`update`/`patch` on `workerdeployments.temporal.io` (no `create`/`delete` - the
CRD's own lifecycle is managed by `k8s/processing-versioned`, not this activity), and one
`RoleBinding` per target namespace (`temporal-oms-apps`, `temporal-oms-processing`,
`temporal-oms-fulfillment`) binding that ClusterRole scoped to just that namespace - not a
`ClusterRoleBinding`, and not `cluster-admin`.

**Per-version image tag was requested but never built.** `applyVersionedDeployment`/
`patchWorkerDeploymentCrd` originally computed `image = imageRepository + ":" + buildId` (e.g.
`apps-worker:v1`). Neither `scripts/kind/app-deploy.sh` nor `scripts/k3d/app-deploy.sh` ever builds
or loads a per-version tag for `apps`/`fulfillment` - only `:latest` - and `processing` only gets an
extra `:v1` tag (a leftover from its older, separate `deploy-processing-workers.sh` CLI demo, not
v2-v4). A promoted pod's image pull silently never happened (`imagePullPolicy: Never`), the pod
never started, its pollers never registered, and `set-current-version` legitimately timed out -
while `OmsVersionRolloutImpl` (next bug below) still reported the step as succeeded. Fixed: always
request `:latest` (`RUNTIME_IMAGE_TAG` in `DeploymentActivitiesImpl`) for every version of every
context this workflow promotes - correct because every version's code already ships in that one
image, the entire point of spec.md's package-per-version convention.

**`currentVersionSet` was computed but never checked.** `deployWorkerVersion` already returns
`currentVersionSet: boolean` - `false` when `set-current-version` timed out without throwing - and
the per-context Advanced UI already surfaces that to the operator. But `OmsVersionRolloutImpl
.runStep` marked a step `SUCCEEDED` the moment the activity call returned at all, never checking the
flag. Fixed: `runStep` now treats `currentVersionSet == false` as a failed step, stopping the
rollout, matching the fail-fast design already documented above.

**Considered and rejected: deriving the workflow class from `TEMPORAL_WORKER_BUILD_ID` instead of a
separate `ACME_*_ORDER_WORKFLOW_CLASS` env var.** Since `TEMPORAL_WORKER_BUILD_ID` is already set
reliably, `acme.<context>.yaml`'s `workflow-classes` entry could just interpolate it directly
(`com.acme.apps.workflows.${TEMPORAL_WORKER_BUILD_ID}.OrderImpl`), dropping the second,
independently-computed env var entirely. Rejected: `workshop/safe-fulfillment-handoff/SOLUTION.md`'s
own exercise deliberately edits code in place inside an *existing* `vN/OrderImpl.java` file and
deploys the result under a **different, decoupled build-id** (its `apps v3`/`processing v3` steps
run `v2.OrderImpl`'s file under build-id `v3`) - collapsing the two into one derived value breaks
that workshop's teaching point, which needs build-id and code-package-version to vary
independently. The two env vars stay separate. (This also means the workflow-class mechanism itself
was never actually the bug in this feature - the image tag above was sufficient on its own to
explain the symptom, since the pod that would have used it never started.)

**Worker Versioning still applied to OMS v1, which spec.md defines as having none at all.**
`deployment-properties.use-versioning: false` does not disable Worker Versioning - confirmed
against `temporal-spring-boot-autoconfigure` 1.38.0's source (`WorkerOptionsTemplate`):
`WorkerOptions.setDeploymentOptions(...)` is called unconditionally whenever a deployment-
name/build-id is configured, regardless of `useVersioning`. The worker still registers a Worker
Deployment version with Temporal either way. The only way to get zero Worker Versioning
registration is for the `deployment-properties` block to be absent from the resolved Spring config
entirely.

Fixed with a new `unversioned` Spring profile per bounded context: `acme.<context>-unversioned.yaml`
(new) defines `spring.temporal.workers` fixed to the `v1` class, with no `deployment-properties`
block at all; `application-unversioned.yaml` (new, matching the existing `application-k8s.yaml`
pattern) imports it. Verified empirically, not assumed: booting `apps-workers` with
`SPRING_PROFILES_ACTIVE=k8s,unversioned` registered exactly one worker for the `apps` task queue
running `v1.OrderImpl`, replacing the versioned worker list rather than merging with it (Spring's
documented behavior for profile-specific `List`-typed properties).

`DeploymentActivitiesImpl.deployWorkerVersion` sets `SPRING_PROFILES_ACTIVE` to
`k8s,unversioned` instead of `k8s` whenever the target build-id is `v1`
(`isUnversionedTarget`/`springProfilesActive`), for every bounded context - this is a template-level
concern, unrelated to which kind of k8s resource represents that context (see next).

**processing's WorkerDeployment CRD can't represent "no Worker Versioning" either - v1 takes the
plain-Deployment path too, not a per-context fixed choice.** The CRD only makes sense when Worker
Versioning is active (the Temporal Worker Controller's whole job is managing a versioned rollout);
there's nothing for it to manage at zero versioning. So `managedByWorkerDeploymentCrd` is no longer
a fixed per-context flag - `deployWorkerVersion` now decides per request (`useCrd = context
.supportsWorkerDeploymentCrd() && !isUnversionedTarget(buildId)`): processing v1 goes through the
same generalized template as apps/fulfillment; processing v2+ keeps patching the CRD. Whichever
path is *not* active is kept at zero pollers, never deleted: promoting processing to v1 scales the
CRD to zero replicas (`scaleWorkerDeploymentCrd`) rather than deleting it - deleting and recreating
the CRD is unnecessary risk (and the delete-protection finalizer's own behavior under a live
controller is not something this design wants to depend on) when a scale-down/scale-back-up
achieves the same "exactly one pooling mechanism live" result. Promoting back to v2+ restores the
CRD's replica count in the same patch that sets its image/env, and `removeStaleVersions` (now called
unconditionally on success, not just on the plain-Deployment path) cleans up the stale plain v1
Deployment/Service.

**Corollary bug, hit live: calling `set-current-version` for an unversioned target never confirms,
because there is no version to make current.** The plain-Deployment branch above called
`setCurrentVersion` unconditionally for every non-CRD promotion, including `v1` - but a pod running
the `unversioned` profile has no `deployment-properties` at all, so it never registers *any* Worker
Deployment build-id with Temporal in the first place. Asking Temporal to set build-id `v1` as
current for a Worker Deployment that was never registered can never succeed, no matter how long the
retry window is - confirmed live (`processing` timed out with exactly this shape: `applyVersionedDeployment`
running as expected, then `Timed out setting processing current version to v1`). Fixed: `deployWorkerVersion`
now skips `setCurrentVersion` entirely for `isUnversionedTarget` and treats the pod coming up as
success directly - the classic, unversioned poller doesn't have or need a "current version." This
applies uniformly to all three contexts' `v1`, not just processing.

**Resolved, hit live and confirmed as a hard Temporal limitation, not a bug: once a Worker Deployment
name has any current version, there is no API to ever clear it back to "none."** `temporal worker
deployment delete-version` refuses while the version is current; `delete` refuses while any version
exists. There is no `unset-current-version` equivalent. And `default-versioning-behavior: PINNED`
means every new workflow start (with no explicit override) pins to whatever's current *at start
time* - permanently, even after that build-id's pollers are gone, since Pinned never falls back.
Confirmed live: `apps`'s `routingConfig.currentVersionBuildID` stayed `"local"` after promoting to
the unversioned `v1` profile (which correctly never calls `setCurrentVersion` - see above), so *every*
new order kept pinning to a build-id with zero live pollers and hung forever, including orders placed
after the promotion, not just ones already in flight.

The root cause wasn't the promotion logic - it's that `scripts/setup-temporal-namespaces.sh`
unconditionally registers `build-id=local` as current for `apps`/`processing`/`fulfillment` on every
Level 2/3 bring-up, before any demo or OMS-rollout action ever happens. That's a one-way door: once
it runs, that namespace's Worker Deployment can never demonstrate spec.md's true "no Worker
Versioning at all" baseline again. Fixed: `SKIP_VERSION_REGISTRATION=1` on that script skips the
three `set-current-version` calls entirely, so `apps`/`fulfillment` start with zero version history -
the OMS-rollout Admin UI's first promotion (even to `v1`) is then the first time Worker Versioning is
engaged at all for that namespace, with no prior pin to collide with.

**Two things this does not cover, by design, not oversight:**
- **`processing` is unaffected by this flag.** Its Worker Versioning is engaged autonomously by the
  Temporal Worker Controller the moment its WorkerDeployment CRD comes up
  (`PROCESSING_WORKER_MODE=versioned`, the `app-deploy.sh` default) - independent of anything this
  script does. A fresh cluster's `processing` starts versioned regardless of
  `SKIP_VERSION_REGISTRATION`. Making processing also start at a genuine unversioned baseline would
  need `app-deploy.sh`'s default mode changed too, a separate decision not made here.
- **Demoting from `v2`+ back to `v1` *after* a real version has already been made current remains
  fundamentally impossible to do cleanly** - the same "no unset" limitation applies no matter which
  script ran at setup time. `SKIP_VERSION_REGISTRATION` only helps a namespace that has *never* had a
  version registered; it is not a general fix for the rollout's existing forward/backward ordering
  design, which assumes any target version (including `v1`) is reachable from any other. In practice:
  the operator's first action against a freshly-baselined cluster must be an Admin UI promotion
  (to whatever starting version) before placing any orders - the raw, unpromoted base pods poll
  successfully but receive zero tasks with nothing yet current, per this repo's own documented
  Worker Versioning behavior (`java/enablements/README.md`).

Not yet verified against a live cluster: the CRD scale-to-zero/restore round trip, and the
`processing`/`fulfillment` variants of the unversioned-profile boot check (only `apps` was verified
directly; the same Spring mechanism applies, but the exact YAML content wasn't re-tested per
context).

#### Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| `enablements-workers` image lacked `kubectl`/`temporal` binaries | Every deploy activity failed with a process-start error | Resolved: moved to `io.kubernetes:client-java` and the SDK's native gRPC stub, no CLI subprocess left in `DeploymentActivitiesImpl` |
| No RBAC existed for cross-namespace Deployment/CRD management | Deploy activity would fail on authorization even with the client library in place | Resolved: `k8s/base/enablements/rbac.yaml` adds a scoped ServiceAccount + ClusterRole + one RoleBinding per namespace |
| `io.kubernetes:client-java`'s CRD API version drifting from the cluster's installed CRD version | Silent patch failures or wrong-shape requests against `workerdeployments.temporal.io` | Pinned `client-java.version=27.0.0` in `java/pom.xml`; still needs a run against a real/local cluster to confirm the read-modify-write on the CRD, not yet done (the k3d cluster used to design this went down before that verification pass) |
| A per-version image tag was requested for apps/fulfillment/processing versions the deploy scripts never build | New pod's image pull never happens, pollers never register, set-current-version times out | Always request the one built `:latest` tag; every version's code already ships in it |
| A failed `set-current-version` (returned `false`, didn't throw) was reported as rollout success | OMS-version rollout silently leaves the previous version current while claiming success | `OmsVersionRolloutImpl.runStep` now checks `currentVersionSet` and fails the step if false |
| `processing`'s CRD-managed rollout was calling `setWorkerDeploymentCurrentVersion` directly with our own build-id string | Confirmed live: the Temporal Worker Controller computes its own build-id (`{imageTag}-{podSpecHash}`, e.g. `latest-f966`) independent of `TEMPORAL_WORKER_BUILD_ID`; our call targeted a build-id that was never registered and could never confirm, while the controller's own Progressive rollout had already completed the promotion on its own | For CRD-managed contexts, `deployWorkerVersion` no longer calls `set-current-version` at all - it patches the CRD, then polls the CRD's own `status.currentVersion.buildID`/`status.targetVersion.buildID` until they match (`waitForWorkerDeploymentCrdRollout`, `crd-rollout-timeout-seconds`, default 180s). Manual/raw-API version control for a bounded context *not* running under a Worker Controller is an intentionally deferred follow-up, not built now. |
| The Temporal Worker Controller's own leader-election flapped between its 2 replica pods on a local cluster, not this code | `processing`'s WorkerDeployment CRD gets permanently stuck (`ManagerIdentity '...ccfb10e7...' does not match user identity '...9414416e...'`) - confirmed the CR's own manager identity changes mid-lifetime, not just across restarts, since a *freshly recreated* CR hit the identical error within under 2 minutes | Operational, not a code fix: scale `temporal-worker-controller-manager` (`temporal-worker-controller-system` namespace) to 1 replica on resource-constrained local clusters, where HA leader election adds no value. Recovery for an already-stuck CRD, if it happens again: `kubectl delete workerdeployment <name>-workers -n <namespace> --timeout=30s` (falls back to stripping the `temporal.io/delete-protection` finalizer via `--type=merge -p '{"metadata":{"finalizers":[]}}'` if that hangs), then reapply the `k8s/processing-versioned` overlay. |

### Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| `DeploymentActivitiesImpl`'s missing manifest template silently breaks every environment that references it | Deploy action throws at runtime | Add the template as part of the same change that wires the new endpoint, with a test that renders it |
| Reusing the deprecated `registerCompatibility()` call as-is would keep shipping a broken mechanism | Version promotion appears to succeed but never actually routes traffic | Replace it with `set-current-version` in the same change, not as a follow-up, and verify via `describe` |
| Coupling load and deploy later without a clear boundary re-introduces the coupling this design avoids | Deploy actions accidentally depend on load state again | Keep the two REST endpoints and their activities fully independent; only combine them behind an explicit future opt-in |
| A fixed promotion order across bounded contexts lands in a transiently unsafe combo during a rollback | Kafka double-publish or dropped `send_fulfillment` mid-rollout | Compute step order from the live current apps version, not a fixed sequence; validate before starting |

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
