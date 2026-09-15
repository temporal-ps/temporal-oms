# Worker Version Enablement Workflow Specification

**Application:** Enablements (new bounded context)
**Feature:** Worker Version Enablement Workflow
**Status:** Draft - Ready for Tech Lead Review
**Owner:** [Your Name]
**Created:** 2026-03-18
**Updated:** 2026-09-14

**Part of:** [Worker Version Enablement Initiative](../INDEX.md)
**Application Structure:** `java/enablements/enablements-core/`

---

## Reconciliation Note (2026-09-14)

Real code already exists for this workflow (`WorkerVersionEnablement`,
`WorkerVersionEnablementImpl`, `OrderActivitiesImpl`,
`DeploymentActivitiesImpl` in `java/enablements/enablements-core`), ahead
of this spec's own tracked progress and diverged from several details
below (interface method names, whether payment submission happens here).
This update does two things at once: corrects the sections that had
drifted from the real code, and reconciles this workflow's order
generation with `SPECS/commerce-payments-apps/spec.md`'s Commerce App and
Payments Processor simulators, so that the same `ScenarioOptions`-driven
delivery behavior (normal, out-of-order, missing-event) used for the
human-driven Svelte checkout demo becomes a reusable, weighted-mix stress
test for worker-version rollouts. It also fixes real bugs found in the
existing code (see "Fixes Applied in This Reconciliation" below).

---

## Reconciliation Note (2026-09-15)

Load generation's control path no longer goes through the `WorkerVersionEnablement`
workflow described below. `enablements-api`'s `LoadGeneratorService` now
starts, observes, and cancels `OrderActivities.runSubmissionLoop` directly as
a [Standalone Activity](https://docs.temporal.io/standalone-activity) via
`ActivityClient`, addressed by Activity ID (the `enablement_id`) on the same
`enablements` task queue -- no owning workflow, no workflow signals. This
lets the web UI control the submission job's lifecycle (start, observe
progress, cancel) directly, exactly matching the SDK cancellation already
built into `runSubmissionLoop`, without the workflow layer in between.

**The `WorkerVersionEnablement` workflow itself, `deployWorkerVersion`, and
`pause()`/`resume()` are untouched** and remain available exactly as
documented below (e.g. via `temporal workflow start`, see
`java/enablements/ENABLEMENT.md`) for the worker-version-transition
demonstration. They are simply no longer what `enablements-api`'s load-gen
endpoints drive.

**Pause/resume have no equivalent on this path and are dropped from the API
and UI.** Temporal's Activity Pause/Unpause operations exist for Standalone
Activities, but per
[Activity Operations](https://docs.temporal.io/activity-operations), they
are "operational controls designed for the CLI, UI, and gRPC API -- not for
programmatic use" via the Client SDK, so there's no clean way to wire a
`pause()`/`resume()` REST endpoint to them. Cancellation
(`ActivityHandle.cancel()`) is a normal, GA SDK operation and replaces the
old `stop()`'s `terminate()` call: it's cooperative, matching
`runSubmissionLoop`'s existing heartbeat-driven cancellation handling, and
preserves the heartbeat-reported submitted count instead of discarding it.

**Endpoints** (`EnablementsController`, `/api/v1/enablements/worker-version`):
`POST /start`, `POST /{enablement_id}/stop`, `GET /{enablement_id}` only.
State is a new, separate `LoadGenerationState` message (`enablement_id`,
`status`, `orders_submitted_count`, sourced from the Standalone Activity's
`describe()`/heartbeat details) -- not `WorkerVersionEnablementState`, whose
`DemoPhase`/`active_versions`/deploy-request fields are workflow/
version-transition concepts that don't apply to a bare activity execution.
`WorkerVersionEnablementState` and its query still serve the workflow path
described in the rest of this document unchanged.

---

## Overview

### Executive Summary

Create the **WorkerVersionEnablement workflow** as a local enablement tool - a Temporal workflow that runs on the user's host and calls the OMS APIs (running in K8s/KinD), demonstrating safe worker versioning.

The workflow:
- Runs locally (not in K8s) - invoked via Temporal CLI or local Java execution
- Acts as an external caller - submits orders to the OMS (apps-api, processing-api running in K8s)
- Works with both local K8s (Minikube/KinD) and cloud Temporal
- Tracks only its own execution state (which version is active, submission rate, demo phase)
- Does NOT duplicate OMS state tracking (orders, completion, failures)
- Supports interactive control signals (transitionToV2) to trigger version changes
- Demonstrates build-id routing live during enablement sessions

**Why this approach:**
Using Temporal to demonstrate Temporal versioning is powerful, but the key insight is: the enablement workflow is just a *caller* of the OMS. It submits orders (like any client would) and lets the OMS do its job. The workflow demonstrates that:
1. Worker versions don't affect external callers (orders submitted successfully)
2. The OMS continues processing regardless of version transitions
3. No failures or dropped orders during the transition

This clarity separates "how we invoke the system" (enablement workflow running locally) from "how the system works" (OMS application running in K8s).

---

## Goals & Success Criteria

### Primary Goals
- Goal 1: WorkerVersionEnablementWorkflow calls OMS APIs continuously (submit → enrich → capture)
- Goal 2: Workflow tracks only its own execution state, not order details (OMS owns that)
- Goal 3: Workflow supports interactive control signals (pause, resume, transitionToV2)
- Goal 4: Workflow triggers v2 worker deployment via activities during session
- Goal 5: Demonstrate safe version transition with zero workflow or OMS failures

### Acceptance Criteria
- [ ] Workflow deploys to enablements-workers in Kubernetes
- [ ] Workflow submits 20 orders at ~12/min to the OMS
- [ ] getState() query returns accurate workflow execution state (phase, submission count, rate, versions)
- [ ] Transitionable from RUNNING_V1_ONLY to RUNNING_BOTH via transitionToV2() signal
- [ ] OMS processes all submitted orders without failures (0 FAILED orders in the OMS)
- [ ] Order completion confirmed via OMS APIs, not workflow state (workflow doesn't track that)
- [ ] Demo scripts and documentation complete

---

## Current State (As-Is)

### What exists today?
- **Scenario scripts** create single orders manually (`submit-order.sh`, `capture-payment.sh`)
- **No continuous ordering** - orders created one-at-a-time for demos
- **No coordinated load generation** - can't trigger version transitions while orders are in flight
- **Manual testing only** - can't easily show "orders flowing during version change"
- **No version transition demo** - can't show v1 → v2 switch safely in action

### Pain points / gaps
- Can't demonstrate version transitions under realistic load
- Hard to show that worker versions don't affect external callers (order clients)
- Difficult to validate system behavior when worker code changes
- No repeatable scenario for enablement/training sessions
- No clear separation between "demo infrastructure" and "production application"

---

## Desired State (To-Be)

### Architecture Overview

```
Enablements Application
├── enablements-core/
│   └── WorkerVersionEnablement workflow (task queue: enablements)
│       ├─ Workflow drives the submission loop directly (Workflow.sleep
│       │  between orders, checks a real paused flag each cycle,
│       │  continueAsNew periodically to keep history bounded)
│       ├─ Per order: pick a DemoScenario from scenario_weights (default:
│       │  mostly NORMAL), then call a short "submit one order" activity
│       ├─ That activity calls the SAME Commerce App / Payments Processor
│       │  REST surface from SPECS/commerce-payments-apps/spec.md:
│       │    POST /api/v1/integrations/commerce/orders   (with ScenarioOptions)
│       │    POST /api/v1/integrations/payments/charges   (+ capture)
│       │  It no longer PUTs/POSTs directly into apps-api's real
│       │  webhooks; real delivery into apps-api now happens later, via
│       │  PublishCartOrders' scheduled tick (see Dependencies below)
│       ├─ Signal: deployWorkerVersion(buildId, replicas) → DeploymentActivities
│       ├─ Signal: pause() / resume() → actually gate the loop
│       └─ Query: getState()
│
├── enablements-workers/
│   └── Worker pool running the workflow
│       • v1 build-id: enablements-worker:v1
│       • v2 build-id: enablements-worker:v2
│
└── enablements-api endpoints (dedicated java/enablements/enablements-api module)
    └─ EnablementsController: start/observe/cancel the runSubmissionLoop
       Standalone Activity directly (see "Reconciliation Note (2026-09-15)"
       above) -- not workflow signals
       • POST /api/v1/enablements/worker-version/start - Start the load-gen Standalone Activity
       • GET /api/v1/enablements/worker-version/{enablement_id} - Get its LoadGenerationState (status, orders submitted)
       • POST /api/v1/enablements/worker-version/{enablement_id}/stop - Cancel it
```

**Data Layer (Protobuf):**

All workflow inputs, outputs, and state are protobuf messages. This is the single source of truth for all data contracts.

```proto
// proto/acme/enablements/v1/worker_version_enablement.proto

// Start a worker versioning enablement demonstration
message StartWorkerVersionEnablementRequest {
  string enablement_id = 1;         // e.g., "demo-session-2026-03-18" (real field name; corrects this spec's earlier "demonstration_id")
  string order_id_seed = 2;         // prefix for generated order IDs; no longer has any behavioral meaning (see Fixes Applied)
  int32 order_count = 3;            // How many orders to process (e.g., 20); currently logged but not enforced, fixed as part of this reconciliation (see Fixes Applied)
  int32 submit_rate_per_min = 4;    // Orders per minute (e.g., 12)
  google.protobuf.Duration timeout = 5;  // How long to run (e.g., 5 minutes); currently logged but not enforced, fixed as part of this reconciliation
  repeated ScenarioWeight scenario_weights = 6; // NEW: weighted mix of DemoScenario for generated orders; empty defaults to mostly NORMAL
}

// Weight for one DemoScenario (from proto/acme/enablements/domain/v1/commerce.proto,
// defined in SPECS/commerce-payments-apps/spec.md) in the generated load's scenario mix.
message ScenarioWeight {
  acme.enablements.domain.v1.DemoScenario scenario = 1;
  int32 weight = 2;
}

// Default scenario mix when scenario_weights is empty: mostly NORMAL, a small
// trickle of the other three, so sustained load exercises realistic messy
// delivery without most orders failing to complete:
//   NORMAL: 85, PAYMENT_BEFORE_COMMERCE: 5, MISSING_COMMERCE_EVENT: 5, MISSING_PAYMENT_EVENT: 5

// Current state of the worker versioning enablement demonstration
// (Order tracking is the responsibility of the OMS application, not this workflow)
message WorkerVersionEnablementState {
  string demonstration_id = 1;

  // Workflow execution state
  enum DemoPhase { RUNNING_V1_ONLY, TRANSITIONING_TO_V2, RUNNING_BOTH, COMPLETE }
  DemoPhase current_phase = 2;

  // Activity metrics
  int32 orders_submitted_count = 3;    // How many times submitOrder() was called
  float orders_per_minute = 4;         // Current submission rate
  repeated string active_versions = 5; // ["v1"] or ["v1", "v2"] depending on phase

  // Versioning info
  google.protobuf.Timestamp last_transition_at = 6;  // When transitionToV2 was signaled
}
```

**Java Generation:**
- `buf generate` creates: `StartWorkerVersionEnablementRequest.java`, `WorkerVersionEnablementState.java`
- Workflow input param: `StartWorkerVersionEnablementRequest` (protobuf)
- Query method returns: `WorkerVersionEnablementState` (protobuf)
- All data serialized/deserialized as protobuf

### Key Capabilities
- **Continuous Submission:** Generate orders at configurable rate
- **State Tracking:** Query workflows to track progress stages
- **Metrics Exposition:** Prometheus-compatible `/metrics` endpoint
- **REST Control:** Start/stop load without code changes
- **Kubernetes Native:** ConfigMap-driven configuration, health checks

---

## Technical Approach

### Design Decisions

| Decision | Rationale | Alternative Considered |
|----------|-----------|------------------------|
| Separate service (not embedded in worker) | Load generation is test infrastructure; keep separate from production code | Embed in processing-workers - couples test logic to production |
| State tracking via Temporal queries | Direct queries give exact, point-in-time state; no external storage needed | External database - adds complexity, eventual consistency issues |
| Metrics via Micrometer/Prometheus | Industry standard; integrates with existing Grafana stack | Custom metrics format - requires custom dashboards |
| ConfigMap-driven load rate | Enables runtime adjustment without code changes | Hardcoded rate - requires redeployment to change |

### Component Design

#### `WorkerVersionEnablement` workflow
- **Purpose:** Orchestrate orders through the system while a team manually
  controls v2 deployment, demonstrating safe worker versioning
  interactively.
- **Interface** (real interface; corrects this spec's earlier
  `WorkerVersionEnablementWorkflow`/`startDemonstration`/`transitionToV2`,
  which never matched the actual code):
  ```java
  @WorkflowInterface
  public interface WorkerVersionEnablement {
    @WorkflowMethod
    void execute(StartWorkerVersionEnablementRequest request);

    @QueryMethod
    WorkerVersionEnablementState getState();

    // Control signals for interactive demo
    @SignalMethod
    void pause();

    @SignalMethod
    void resume();

    @SignalMethod
    void deployWorkerVersion(DeployWorkerVersionRequest cmd); // generalized: carries buildId + replicas, not a fixed "v2"
  }
  ```
- **Workflow Logic** (restructured in this reconciliation; the loop now
  lives in the workflow itself, not inside one giant activity):
  - **Submission loop:** for each order up to `order_count` (or until
    `timeout` elapses, both are now actually enforced, see Fixes
    Applied), `Workflow.sleep` to pace to `submit_rate_per_min`, check the
    real `paused` flag (skip the sleep-and-submit cycle while paused,
    honoring `pause()`/`resume()` for real), pick a `DemoScenario` from
    `scenario_weights` by weighted random choice, then call the short
    `submitOneOrder` activity (see Activities below) with that scenario.
    Call `Workflow.continueAsNew` periodically (e.g. every 100 orders) to
    keep workflow history bounded across a long sustained run.
  - **On `deployWorkerVersion(cmd)` signal:** append `cmd` to a queue and
    process it with a *correct* index (this reconciliation fixes the
    existing off-by-one that throws `IndexOutOfBoundsException` on any
    signal, see Fixes Applied): trigger
    `deployWorkerVersion(cmd.getBuildId(), cmd.getReplicas())` (using the
    signal's real fields, not hardcoded `v2`/`1`) and
    `registerCompatibility()`. Update `DemoPhase` →
    `TRANSITIONING_TO_V2` → `RUNNING_BOTH` once deployment completes.
  - **Responsibilities:** drive order generation at the configured rate
    and scenario mix; let the OMS handle all real processing
    (enrichment, payment capture, completion); track only workflow
    execution state (phase, submission count, rate, scenario mix);
    respond to control signals for real; provide state via `getState()`
    (order tracking remains the OMS app's job, queried separately).

#### Activities (enablements-core)

**`submitOneOrder` activity** (replaces the old `submitOrders`'s internal
infinite loop; this activity now does one order per call, is short and
idempotent, and no longer needs a heartbeat since it doesn't loop):
- Picks a real catalog item ID from `commerce-catalog.json` and one of a
  small set of canned addresses (replacing the old single hardcoded
  address), builds `CreateCommerceOrderRequest` with the `ScenarioOptions`
  the workflow chose, and calls
  `POST /api/v1/integrations/commerce/orders` (the Commerce App backend
  from `SPECS/commerce-payments-apps/spec.md`), not `apps-api` directly.
- Calls `POST /api/v1/integrations/payments/charges` (+ capture) on the
  Payments Processor backend, using an "approved" test card number by
  default.
- Returns the generated order ID; real delivery into `apps-api`'s webhooks
  happens later, asynchronously, via `PublishCartOrders`' scheduled tick
  (not synchronously in this activity, unlike the old direct-PUT/POST
  behavior).
- Retains exponential-backoff retry on the HTTP calls to the Commerce
  App / Payments Processor backends themselves (these can still fail
  transiently; that's unrelated to the scenario system, which governs
  *delivery into apps-api*, not *reachability of these backends*).

#### Query Handler
- **Purpose:** Expose workflow execution state during demo (not order tracking—that's the OMS app's job)
- `getState()` returns:
  - Current demo phase (RUNNING_V1_ONLY, TRANSITIONING_TO_V2, RUNNING_BOTH, COMPLETE)
  - Orders submitted count (how many times submitOrder() was called)
  - Current submission rate (orders/min)
  - Active worker versions (v1 only, or both v1+v2)
  - **Note:** Order tracking (completion, failure, state progression) is the OMS app's responsibility—query apps-api or processing-api for that

### Fixes Applied in This Reconciliation

The real code (`WorkerVersionEnablementImpl.java`,
`OrderActivitiesImpl.java`, `DeploymentActivitiesImpl.java`) had drifted
ahead of this spec and carried real bugs, confirmed by direct code
reading. This reconciliation fixes all four in the same pass as the
Commerce App/Payments Processor integration, since the submission loop
was being rewritten anyway:

| Bug | Where | Fix |
|---|---|---|
| `execute()`'s deploy-request replay loop starts at an out-of-bounds index (`deployRequestsCount()` instead of `deployRequestsCount() - 1`), throwing `IndexOutOfBoundsException` on any `deployWorkerVersion` signal | `WorkerVersionEnablementImpl.java:86-91` | Correct the loop bound; process each queued `DeployWorkerVersionRequest` exactly once, in order received |
| `pause()`/`resume()` only log; they never gate the submission loop | `WorkerVersionEnablementImpl.java:102-110` | Moving the loop into the workflow (see Workflow Logic above) lets it check a real `paused` flag each cycle, set/cleared by these signals |
| `DeploymentActivitiesImpl.deployWorkerVersion` hardcodes `{BUILD_ID}` → `"processing-worker:v2"` and `{REPLICAS}` → `1`, ignoring the signal's actual fields | `DeploymentActivitiesImpl.java:37-50` | Use `cmd.getBuildId()`/`cmd.getReplicas()` from the signal instead of the hardcoded constants |
| No `continueAsNew` anywhere; the workflow blocks forever via `Workflow.await(() -> false)` once submission "completes" (which itself never happens, since the old `submitOrders` activity looped via `Thread.sleep` until cancelled) | `WorkerVersionEnablementImpl.java:93-94` | Loop lives in the workflow now, with `Workflow.continueAsNew` called periodically; `order_count`/`timeout` are enforced as real loop-exit conditions instead of being logged and ignored |

### Configuration Model

Corrected to match the real `acme.enablements.yaml`: submission rate,
order count, timeout, and scenario mix are **workflow input fields**
(`StartWorkerVersionEnablementRequest`), not static config; this spec's
earlier `order-rate`/`submission-timeout` yaml keys never existed in the
actual configuration and are removed here. The real static config is:

```yaml
# java/enablements/enablements-core/src/main/resources/acme.enablements.yaml
enablements:
  api:
    base-url: ${ENABLEMENTS_API_BASE_URL:http://localhost:8050}   # Commerce App / Payments Processor backend
  apps-api:
    base-url: http://localhost:8080                               # real apps-api (target of PublishCartOrders' subscribers, not called directly by this workflow anymore)
  processing-api:
    base-url: http://localhost:8070
  deployment:
    namespace: temporal-oms-processing
    manifest-template: k8s/base/processing/processing-workers-deployment-template.yaml
    kubeconfig: ""

spring.temporal:
  connection:
    target: ${TEMPORAL_ENABLEMENTS_ADDRESS:localhost:7233}
    api-key: ${TEMPORAL_ENABLEMENTS_API_KEY:}
  namespace: ${TEMPORAL_ENABLEMENTS_NAMESPACE:default}
  workers:
    - task-queue: enablements
      workflow-classes:
        - com.acme.enablements.workflows.WorkerVersionEnablementImpl
      activity-beans:
        - order-activities
        - deployment-activities
```

### Data Model

**Order Record:**
```java
class OrderRecord {
  String orderId;           // UUID
  LocalDateTime created;    // when submitted
  WorkflowState state;      // created | processing | completed | failed
  LocalDateTime lastQueried; // last state check time
  String lastError;         // if failed
}

enum WorkflowState {
  CREATED,                  // submitted, awaiting processing
  PROCESSING,               // in-flight in workflow
  COMPLETED,                // workflow finished successfully
  FAILED,                   // workflow errored or stuck
}
```

**Metrics Exposed:**
```
load_gen_orders_created_total    {counter}  # Total submitted
load_gen_orders_processing       {gauge}    # Currently in-flight
load_gen_orders_completed_total  {counter}  # Finished successfully
load_gen_orders_failed_total     {counter}  # Failed/stuck
load_gen_submission_rate_sec     {gauge}    # Current submission rate
load_gen_completion_rate_percent {gauge}    # Completed / Created %
```

### Deployment Model

**Local Execution (Not Kubernetes):**
The enablement workflow runs on the user's local machine and calls the OMS APIs in K8s/KinD. No Kubernetes deployment needed.

**Invocation (from local host):**

**Option 1: Via Temporal CLI (requires temporal CLI installed)**
```bash
temporal workflow start \
  --workflow-id demo-session-1 \
  --namespace apps \
  --type WorkerVersionEnablementWorkflow \
  --task-queue enablements \
  --input '{"demonstration_id":"demo-session-1","order_count":20,"submit_rate_per_min":12,"timeout":"5m"}'
```

**Option 2: Via local Java execution**
```bash
cd java/enablements/enablements-core
mvn exec:java -Dexec.mainClass="com.acme.enablements.LocalEnablementRunner" \
  -Dexec.args="demo-session-1 20 12 5m"
```

**Option 3: Via optional EnablementsController in apps-api**
```bash
# Controller accepts protobuf message (encoded as JSON for convenience)
curl -X POST http://localhost:8080/api/v1/enablements/worker-version/demo-session-1/start \
  -H "Content-Type: application/json" \
  -d '{"demonstration_id":"demo-session-1","order_count":20,"submit_rate_per_min":12,"timeout":"300s"}'
```
(Protobuf JSON encoding - same message structure as defined in proto)

**Configuration:**
- OMS APIs: `APPS_API_ENDPOINT` (default: http://localhost:8080 or tunneled Traefik)
- Processing API: `PROCESSING_API_ENDPOINT` (default: http://localhost:8070 or tunneled)
- Temporal: `TEMPORAL_TARGET` (default: localhost:7233 for local, or cloud Temporal)

---

## Implementation Strategy

### Phase 1: Proto Definitions & Core Workflow
**Goal:** Define data contracts and implement workflow

Deliverables:
- [ ] Proto file: `proto/acme/enablements/v1/worker_version_enablement.proto`
  - LoadTestRequest, OrderState, LoadTestStatus messages
  - Code generation: creates Java POJOs
- [ ] Maven module: `java/enablements/enablements-core/pom.xml`
- [ ] Workflow interface: `WorkerVersionEnablementWorkflow.java`
- [ ] Implementation: `WorkerVersionEnablementWorkflowImpl.java` (v1)
  - Loop: submit order → process → track state
  - Query method: getStatus()
  - Signal methods: pause(), resume()
- [ ] Activity interfaces and implementations
  - OrderActivities: submitOrder(orderId)
  - ProcessingActivities: enrichOrder(), capturePayment()
- [ ] Unit tests (mocked HTTP calls to apps-api/processing-api)

### Phase 2: Local Workflow Execution
**Goal:** Enable running the workflow from local host

Deliverables:
- [ ] LocalEnablementRunner.java - Main entry point to run workflow locally
  - Takes arguments: demonstration_id, order_count, submit_rate_per_min, timeout
  - Configures WorkflowClient for local or remote Temporal
  - Starts WorkerVersionEnablementWorkflow and tracks execution
- [ ] Configuration via environment variables:
  - `TEMPORAL_TARGET`: Temporal server address (local or cloud)
  - `APPS_API_ENDPOINT`: OMS apps-api URL
  - `PROCESSING_API_ENDPOINT`: OMS processing-api URL
- [ ] Maven configuration: `java/enablements/enablements-core/pom.xml` includes exec plugin
- [ ] Shell script: `scripts/run-enablement.sh` (optional - simplifies invocation)
- [ ] Integration tests (local Temporal workflow execution against real K8s APIs)

### Phase 3: Enablements Controller (Optional - for monitoring API)
**Goal:** Add REST endpoints to apps-api for workflow control and monitoring

Deliverables:
- [ ] EnablementsController in `java/apps/apps-api/src/main/java/com/acme/enablements/controllers/`
  - Separate controller (not conflated with CommerceWebhookController)
  - Inject WorkflowClient (reuse from apps-api Spring config)
  - Endpoints:
    - `GET /api/v1/enablements/worker-version/{enablement_id}` - Returns `WorkerVersionEnablementState` protobuf (JSON encoded)
    - `POST /api/v1/enablements/worker-version/{enablement_id}/start` - Takes `StartWorkerVersionEnablementRequest` protobuf (JSON encoded)
    - `POST /api/v1/enablements/worker-version/{enablement_id}/pause` - Sends pause signal
    - `POST /api/v1/enablements/worker-version/{enablement_id}/transition-to-v2` - Sends transitionToV2 signal
- [ ] Uses protobuf messages directly (no separate DTOs) - Spring handles JSON serialization of protobuf
- [ ] Unit tests
- [ ] **Can be deferred** if using Temporal CLI or direct workflow execution for demo

### Phase 4: Versioning & Demo Scripts
**Goal:** Create v2 workflow and demo automation

Deliverables:
- [ ] `WorkerVersionEnablementWorkflowImplV2.java`
  - Same logic as v1 (or with enhancements for teaching)
  - Registered with build-id: `enablements-worker:v2`
- [ ] Demo scripts:
  - `scripts/start-enablement-workflow.sh` - Start v1 workflow
  - `scripts/deploy-enablement-v2.sh` - Deploy v2 workers, register compatibility
  - `scripts/query-enablement-status.sh` - Monitor progress
- [ ] Talk track document: `docs/enablements/worker-versioning-talk-track.md`
- [ ] Runbook: `docs/enablements/worker-versioning-runbook.md`

### Critical Files / Modules

**Proto Definitions (New):**
- `proto/acme/enablements/v1/worker_version_enablement.proto`
  - `StartWorkerVersionEnablementRequest` message (input to workflow)
  - `WorkerVersionEnablementState` message (output from workflow queries—workflow execution state only, not order tracking)

**To Create (enablements-core):**
- `java/enablements/enablements-core/pom.xml`
- `java/enablements/enablements-core/src/main/java/com/acme/enablements/workflows/WorkerVersionEnablementWorkflow.java`
- `java/enablements/enablements-core/src/main/java/com/acme/enablements/workflows/WorkerVersionEnablementWorkflowImpl.java` (v1)
- `java/enablements/enablements-core/src/main/java/com/acme/enablements/activities/OrderActivities.java`
- `java/enablements/enablements-core/src/main/java/com/acme/enablements/activities/ProcessingActivities.java`

**To Create (local execution):**
- `java/enablements/enablements-core/src/main/java/com/acme/enablements/LocalEnablementRunner.java`
  - Main entry point (public static void main) to run workflow locally
  - Configures WorkflowClient, starts workflow, monitors execution
- `scripts/run-enablement.sh` (optional - convenience wrapper)
  - Sets env vars, calls LocalEnablementRunner with arguments

**To Create (enablements controller - optional, in apps-api):**
- `java/apps/apps-api/src/main/java/com/acme/enablements/controllers/EnablementsController.java`
- `java/apps/apps-api/src/main/java/com/acme/enablements/dto/` (Response DTOs)
  - `WorkerVersionEnablementStateDto.java`
  - `EnablementResponse.java`
- Tests for EnablementsController

**To Create (Parent):**
- `java/enablements/pom.xml` (parent pom with modules: core, workers)

**To Modify:**
- `java/pom.xml` - Add enablements module
- `proto/acme/pom.xml` - Add enablements proto generation
- `java/apps/apps-api/pom.xml` - (if implementing EnablementsController in apps-api for remote invocation)
- `java/apps/apps-api/src/main/java/com/acme/apps/ApiApplication.java` - (if implementing controller)

---

## Testing Strategy

### Unit Tests
- OrderSubmitter generates valid order IDs
- OrderSubmitter handles submission errors (retries, timeout)
- WorkflowStateTracker parses workflow state correctly
- MetricsCollector accumulates metrics accurately
- LoadController REST endpoints respond correctly

### Integration Tests
- Workflow connects to Temporal cluster and executes activities
- Can query real workflow state via Temporal SDK
- OrderActivities successfully call apps-api and processing-api
- ProcessingActivities successfully call processing-api (or use test doubles)
- EnablementsController (if implemented) correctly queries and signals workflows via REST API

### Demonstration Scenario

**Scenario: Worker Versioning Enablement Under Realistic Load**

1. **Initialize:** Start `WorkerVersionEnablement` v1
   - Script or curl: `POST http://localhost:8080/api/v1/enablements/worker-version/demo-session-1/start` (or use Temporal SDK directly)
   - Request: 20 orders, 12/min rate, 5-minute duration, default
     `scenario_weights` (mostly `NORMAL`, a small trickle of the other
     three presets); omit `scenario_weights` for the default mix, or
     pass a fixed single-scenario list (e.g. all `MISSING_PAYMENT_EVENT`)
     to stress-test one failure mode deliberately
   - DemoPhase: RUNNING_V1_ONLY
   - Workflow begins generating orders through the Commerce App/Payments
     Processor backends (`SPECS/commerce-payments-apps/spec.md`), which
     deliver into apps-api's real webhooks on `PublishCartOrders`' next
     scheduled tick

2. **Monitor Workflow State:** Query workflow state every 10 seconds
   - Script or curl: `GET http://localhost:8080/api/v1/enablements/worker-version/demo-session-1` (or use Temporal SDK)
   - Verify: Workflow submitting at ~12/min
   - Verify: DemoPhase is RUNNING_V1_ONLY
   - Verify: active_versions shows ["v1"]

3. **Monitor OMS:** In parallel, watch the OMS application
   - Orders appearing in apps-api `/orders` endpoint
   - Orders progressing through enrichment and payment capture in processing-api
   - Temporal UI shows: OrderSubmissionWorkflow, EnrichmentWorkflow, PaymentWorkflow (the real business workflows)

4. **Transition (at ~2 min mark):** Deploy v2 workers, mark compatible
   - Script or curl: `POST http://localhost:8080/api/v1/enablements/worker-version/demo-session-1/transition-to-v2` (sends signal to workflow)
   - Workflow activity deployV2Workers() applies WorkerDeployment v2 via Temporal Worker Controller
   - Workflow activity registerCompatibility() sets up Temporal build-ids
   - DemoPhase transitions to: TRANSITIONING_TO_V2 → RUNNING_BOTH

5. **Observe Transition:** Continue monitoring
   - Workflow state: DemoPhase = RUNNING_BOTH, active_versions = ["v1", "v2"]
   - OMS continues processing orders (submit/enrich/capture)
   - Orders submitted before transition continue on v1 workers
   - Orders submitted after transition may execute on v2 workers (build-id routing)
   - **Key point:** No failures, no dropped orders—the OMS app is unaffected by the version change

5. **Complete:** Workflow reaches timeout/completion
   - Workflow final state: COMPLETE
   - Demonstrates: Safe worker version transition with zero downtime
   - OMS has processed all submitted orders (verify via OMS app, not workflow)

**Acceptance Criteria:**
- [ ] Workflow starts and runs smoothly
- [ ] getState() query returns accurate workflow execution state
- [ ] Workflow submits orders at configured rate (~12/min)
- [ ] DemoPhase transitions correctly (RUNNING_V1_ONLY → TRANSITIONING_TO_V2 → RUNNING_BOTH → COMPLETE)
- [ ] active_versions correctly reflects current versioning state
- [ ] No workflow failures during version transition
- [ ] OMS processes all submitted orders (0 FAILED in the OMS, not the workflow)
- [ ] Graceful shutdown: workflow finishes cleanly
- [ ] Demo clearly shows: enablement calls OMS, OMS handles everything else

---

## Risks & Mitigation

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|-----------|
| Workflow order submission fails during demo | High | Low | Test with same rate/config before live session; have fallback demo order flow |
| v2 deployment takes too long, breaks demo flow | Medium | Low | Pre-stage v2 deployment, test timing offline; use smaller order count if needed |
| Apps-api or processing-api becomes unavailable | High | Low | Health checks before session; verify OMS services running |
| Workflow crashes mid-demo | High | Low | Implement error handling and retries in activities; monitor Temporal logs during session |
| Order failures in OMS (not workflow's fault) | Medium | Medium | Not the workflow's responsibility, but communicate that to team; demo focuses on version transition, not order success |

---

## Dependencies

### External Dependencies
- Spring Boot 3.5+ (web starter)
- Temporal Java SDK 1.33+
- Micrometer Prometheus (metrics)

### Cross-Cutting Concerns
- **apps-api:** Must be running in K8s (order submission now happens
  indirectly, via `PublishCartOrders`' scheduled delivery, not a direct
  call from this workflow)
- **`SPECS/commerce-payments-apps/spec.md`'s Commerce App / Payments
  Processor backends:** Must be running and reachable at
  `enablements.api.base-url`; this workflow is now their second consumer
  alongside the Svelte checkout demo
- **Temporal cluster:** Must be running (local K8s, or cloud Temporal)
- **Networking:** Local machine must reach Temporal, `enablements-api`,
  apps-api, and processing-api (via tunnel or direct)

### Rollout Blockers
- [ ] Temporal cluster deployed and running (K8s or cloud)
- [ ] `apps` namespace in Temporal created
- [ ] apps-api deployed in K8s and accessible from local machine
- [ ] processing-api deployed in K8s and accessible from local machine
- [ ] Tunnel or port-forwarding setup (if using local K8s)
- [ ] **`PublishCartOrders`' webhook subscriber list must include
      `apps-api`'s `CommerceWebhookController`/`PaymentsWebhookController`
      URLs** (`enablements.webhooks.subscribers` in
      `acme.enablements.yaml`, per `SPECS/commerce-payments-apps/spec.md`).
      This is required specifically for this workflow's purpose (proving
      the real `apps.Order`/`processing.Order` workflows survive a
      version transition); the general demo/UI checkout path can still
      run with that list empty if desired.

---

## Open Questions & Notes

### Questions for Tech Lead / Product

- [ ] Demo duration: 5 minutes suitable for session? Or should be configurable?
- [ ] Order count: 20 orders good for demo? Or scale to show more?
- [ ] Submit rate: 12/min feels natural? Or adjust for pacing?
- [ ] Should Phase 2 transition to RUNNING_BOTH immediately after v2 deployment, or wait for explicit confirmation signal?

### Implementation Notes

- **Order ID format:** Use UUID v4 for safety (no conflicts)
- **Submission timing:** Use `CancellationScope.withTimeout()` to enforce order submission rate (12/min = submit every 5 sec)
- **DemoPhase transitions:**
  - RUNNING_V1_ONLY → TRANSITIONING_TO_V2: on `transitionToV2()` signal (team manually triggers)
  - TRANSITIONING_TO_V2 → RUNNING_BOTH: after `deployV2Workers()` and `registerCompatibility()` activities complete
  - RUNNING_BOTH → COMPLETE: after all orders submitted and timeout reached
- **Activity execution tracking:** Orders inherit worker_version from activity context (Temporal tracks which worker executed activity)
- **Workflow state size:** `WorkerVersionEnablementState` with 50 recent orders ~5KB; well within Temporal event size limits
- **Error handling:** If activity fails (apps-api down), retry with exponential backoff per Temporal defaults; continue with remaining orders
- **V2 Deployment:** deployV2Workers activity uses Temporal Worker Controller (WorkerDeployment CRD) to deploy v2 workers. Pre-existing WorkerDeployment manifest for v2 must be available in the cluster; activity applies it via kubectl

---

## References & Links

- [Worker Version Enablement Initiative](../INDEX.md)
- `SPECS/commerce-payments-apps/spec.md` - Commerce App/Payments
  Processor simulators, `ScenarioOptions`/`DemoScenario`,
  `PendingPublishRegistry`, `PublishCartOrders` (this workflow's
  order-generation path since this reconciliation)
- `java/enablements/ENABLEMENT.md` - live runbook that starts this
  workflow (`temporal workflow start`); its example input was updated to
  use `scenario_weights` instead of the removed `orderIdSeed: "invalid"`
  hack
- [Temporal Java SDK Docs](https://docs.temporal.io/dev-guide/java)
- [Micrometer Prometheus](https://micrometer.io/docs/registry/prometheus)
- [Spring Boot Health Checks](https://spring.io/guides/gs/actuator-service/)
