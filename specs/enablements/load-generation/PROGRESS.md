# Worker Version Enablement Workflow - Progress Tracking

**Spec:** [spec.md](./spec.md)
**Status:** ✅ Reconciliation implemented (2026-09-14); load-gen control path moved off the workflow onto a Standalone Activity (2026-09-15); see "Implementation Notes" below and spec.md's "Reconciliation Note (2026-09-15)"
**Owner:** [Your Name]
**Initiative:** [Worker Version Enablement](../INDEX.md)
**Subdirectory:** `load-generation/` (historical name; contains core workflow + core module)

---

## Current Status

**Correction (2026-09-14):** this table previously said "Not started" for
Phase 1/2, which was stale. Real code already exists
(`WorkerVersionEnablement`, `WorkerVersionEnablementImpl`,
`OrderActivitiesImpl`, `DeploymentActivitiesImpl` in
`java/enablements/enablements-core`) and had diverged from the spec
(different interface method names, direct HTTP calls into `apps-api`
instead of the Commerce App/Payments Processor simulators, and four real
bugs). See `spec.md`'s "Reconciliation Note" and "Fixes Applied in This
Reconciliation" sections.

| Component | Status | Owner |
|-----------|--------|-------|
| Spec document | ✅ Complete, reconciled with `SPECS/commerce-payments-apps/spec.md` | [Your Name] |
| API path clarification | ✅ Complete (endpoints → apps-api controller) | [Your Name] |
| Tech lead review | ⏳ Awaiting (now including the reconciliation) | [Tech Lead] |
| Implementation planning | ⏳ Blocked (pending approval) | TBD |
| Phase 1: Proto + Workflow | ✅ `scenario_weights`/`ScenarioWeight` added; all four documented bugs fixed; loop moved into the workflow | TBD |
| Phase 2: EnablementsWorkers | ✅ `OrderActivitiesImpl.submitOneOrder` now calls the Commerce App/Payments Processor backends via `enablements.api.base-url`, not `apps-api` | TBD |
| Phase 3: EnablementsController | ⏳ Not started (still optional per spec) | TBD |
| Phase 4: V2 + Demo scripts | 🔶 `ENABLEMENT.md` runbook exists and matches `scenario_weights`; formal demo scripts (`scripts/start-enablement-workflow.sh` etc.) not started | TBD |

### Known bugs in the existing implementation (fixed 2026-09-14)
- [x] `execute()`'s deploy-request replay loop off-by-one threw `IndexOutOfBoundsException` on any `deployWorkerVersion` signal (`WorkerVersionEnablementImpl.java:86-91`). Fixed by moving deploy-request processing into `processQueuedDeployRequests()`, which drains the queue from index 0 instead of iterating an out-of-bounds range.
- [x] `pause()`/`resume()` signals were no-ops. Fixed: the submission loop now lives in the workflow and calls `Workflow.await(() -> !paused)` before each pacing sleep, so `pause()` genuinely blocks further submissions until `resume()`.
- [x] `DeploymentActivitiesImpl.deployWorkerVersion` hardcoded `processing-worker:v2`/replicas=1. Fixed: uses `cmd.getBuildId()`/`cmd.getVersion()`/`cmd.getReplicaCount()` from the signal.
- [x] No `continueAsNew`; the workflow used to block forever via `Workflow.await(() -> false)`. Fixed: the loop now exits once `order_count`/`timeout` are reached, and calls `Workflow.newContinueAsNewStub` every 100 submitted orders to keep history bounded on long sustained runs.
- [x] `OrderActivitiesImpl.submitOrders` used to PUT/POST directly into `apps-api`'s real webhooks with a crude `orderIdSeed.contains("invalid")` string-match "scenario." Replaced by `submitOneOrder`, a short one-order-per-call activity that calls the Commerce App (`POST .../commerce/orders`) and Payments Processor (`POST .../payments/charges`) from `SPECS/commerce-payments-apps/spec.md`, carrying the workflow-chosen `DemoScenario`.

### Additional findings fixed in the same pass (not in the original four-bug list)
- `WorkerVersionEnablementState.current_phase`/`active_versions` were never
  set anywhere in the real code (`getState()` always returned
  `DEMO_PHASE_UNSPECIFIED` and an empty version list). Now set: starts
  `RUNNING_V1_ONLY`/`["v1"]`, moves to `TRANSITIONING_TO_V2` on receiving a
  `deployWorkerVersion` signal, `RUNNING_BOTH` once that deployment
  completes (with the new build ID appended to `active_versions`), and
  `COMPLETE` when the loop ends.
- The original code only processed queued deploy requests once, after all
  order submission finished, which cannot match the documented demo flow
  (`transitionToV2` at the ~2-minute mark of a 5-minute run, order
  submission continuing throughout). `processQueuedDeployRequests()` now
  runs once per loop iteration too, so a signal is applied promptly instead
  of only at the very end.

---

## Spec Completion Checklist ✅

- [x] Executive summary written
- [x] Goals & acceptance criteria defined
- [x] Current state analysis
- [x] Desired state architecture
- [x] Technical design (components, configuration, data model)
- [x] Implementation phases (4 phases with deliverables)
- [x] Testing strategy (unit, integration, load test)
- [x] Risk assessment
- [x] Open questions documented

---

## Open Items Before Approval

### Questions Requiring Tech Lead Input

- [ ] Order submission rate: 12 orders/min suitable for demo? Adjust for pacing?
- [ ] Order count: 20 orders good? Scale for longer demo visibility?
- [ ] Demo duration: ~5 minutes from v1-only to completion?
- [ ] DemoPhase transition timing: Auto-transition TRANSITIONING_TO_V2 → RUNNING_BOTH after v2 deployment, or explicit signal?
- [ ] Activity error handling: Retry failed order submissions or skip and continue?

### Design Decisions to Validate

- [ ] Workflow-based demo (proposed: yes - self-referential teaching)
- [ ] Order version tracking via activity context (proposed: yes - Temporal tracks which worker executed)
- [ ] API endpoints in apps-api as separate controller (proposed: yes - isolated from commerce paths)

---

## Tech Lead Review

### Submission Checklist
- [x] Spec is self-contained (can understand without context)
- [x] Goals are measurable
- [x] Acceptance criteria are testable
- [x] Design decisions documented with rationale
- [x] Implementation is appropriately scoped
- [x] Risks identified and mitigated
- [x] No blocking dependencies on other specs

### Review Feedback
```
[Tech Lead: Add feedback here]
```

### Approval Decision
```
☐ APPROVED - Proceed to planning
☐ APPROVED WITH CHANGES - See feedback above, proceed after revisions
☐ NEEDS REWORK - Return to author for major revisions

Approved By: ________________    Date: ________________
```

---

## Next Steps (After Approval)

1. **Planning Phase**
   - Break 4 implementation phases into detailed tasks
   - Create task list with estimates
   - Assign owner + target dates
   - Create Jira/Linear tickets

2. **Phase 1 Implementation** (Core Service)
   - Maven module scaffolding
   - Spring Boot application
   - Configuration files
   - Basic unit test

3. **Phase 2 Implementation** (Submission & Tracking)
   - OrderSubmitter service
   - WorkflowStateTracker service
   - Integration tests with Temporal

4. **Phase 3 Implementation** (Metrics & API)
   - MetricsCollector
   - LoadController REST endpoints
   - REST API tests

5. **Phase 4 Implementation** (Kubernetes & Docs)
   - Dockerfile
   - Kubernetes manifests
   - Integration into deploy script
   - Documentation

6. **Testing & Demo**
   - Load test scenario (300 orders over 5 min)
   - Validate metrics accuracy
   - Demo to team

---

## Timeline Estimate

(To be refined after planning)

| Phase | Estimate | Owner | Status |
|-------|----------|-------|--------|
| Approval & Planning | 1 day | [TBD] | ⏳ Blocked |
| Phase 1: Core Service | 2 days | [TBD] | ⏳ Blocked |
| Phase 2: Submission & Tracking | 3 days | [TBD] | ⏳ Blocked |
| Phase 3: Metrics & API | 2 days | [TBD] | ⏳ Blocked |
| Phase 4: K8s & Docs | 3 days | [TBD] | ⏳ Blocked |
| **Total** | **~11 days** | | |

---

## Dependencies

### Blocks This Sub-Spec
- ✅ None (can start independently)

### Blocked By
- ⏳ Tech lead approval
- ⏳ `PublishCartOrders`' webhook subscribers pointed at `apps-api` (new,
  per this reconciliation; required for this workflow specifically, see
  `spec.md`'s Dependencies section)

### External Dependencies
- ✅ Temporal cluster deployed
- ✅ apps-api service available
- ✅ KinD cluster running
- ⏳ `SPECS/commerce-payments-apps/spec.md`'s Commerce App/Payments
  Processor backends built and running (new, per this reconciliation)

---

## Notes for Tech Lead

**Critical Insight: Enablement is an External Caller**
The enablement workflow is NOT part of the OMS. It's an external caller (like a client/user would be) that:
- Calls OMS APIs to submit orders
- Watches the version transition happen
- Demonstrates that the OMS is unaffected by worker version changes

This is the key teaching moment: "Worker versions don't affect my ability to submit orders or any application behavior."

**No State Duplication:**
- **Enablement workflow owns:** execution phase, submission count, submission rate, active versions
- **OMS app owns:** order state, completion, failure, enrichment, payment capture
- The workflow does NOT track "completed orders" or "failed orders"—that's the OMS's job
- This clean separation prevents confusion and keeps the demo focused on versioning, not order tracking

**Interactive Demo Flow:**
1. Enablement workflow starts submitting orders (calls OMS APIs)
2. Team observes orders flowing through OMS (via OMS UI or APIs)
3. Team triggers `transitionToV2()` signal
4. Enablement workflow deploys v2 workers
5. Orders continue flowing through OMS (same as before)
6. Team observes: version changed, orders unaffected

**Local Execution, Not K8s:**
This is a local development/demo tool. It runs on your host machine and calls the OMS APIs in K8s. Benefits:
- No need to deploy extra services to K8s
- Easy to iterate and test locally
- Works with both local (Minikube/KinD) and cloud Temporal
- Simple invocation: `mvn exec:java` or `temporal workflow start`

**Scope:**
This spec defines the core enablement workflow + activities + local runner. Version deployment (v2 workers) and validation framework are separate sub-specs that build on this.

---

## Implementation Notes (2026-09-14)

- **`OrderActivities`/`OrderActivitiesImpl` class names unchanged**; only
  the activity method was renamed (`submitOrders` → `submitOneOrder`). The
  spec text describes the new activity's behavior in detail but never
  states a new class name; the task's own instruction to "rename per the
  spec" is read as the method rename the spec text actually documents.
  Flagging this reading explicitly since no literal new class name exists
  to verify it against.
- **`SubmitOrdersRequest`/`SubmitOrdersResponse` proto messages removed**
  and replaced with `SubmitOneOrderRequest`/`SubmitOneOrderResponse`
  (`proto/acme/enablements/v1/worker_version_enablement.proto`), matching
  the activity's new one-order-per-call shape.
- **The `orderIdSeed.contains("invalid")` → processing-api validation-complete
  hack is fully removed**, including the `Thread.ofVirtual()` delayed-call
  code path in the old `OrderActivitiesImpl`; nothing replaces it, per the
  spec's own statement that `order_id_seed` is now just a naming prefix
  with no behavioral meaning.
- **Catalog item and shipping address selection in `submitOneOrder`** picks
  a random item from the real `CommerceCatalogFixtureService` (injected
  directly, since both live in `enablements-core`) and a random address
  from four hardcoded canned addresses, replacing the old single hardcoded
  San Francisco address.
- **HTTP retry is Temporal's default activity retry** (exponential backoff,
  applied by `WorkerVersionEnablementImpl`'s `ActivityOptions` for
  `OrderActivities`), not hand-rolled retry logic inside the activity.
  Matches the spec's "retains exponential-backoff retry" language without
  re-implementing what Temporal already provides for a failed activity
  call.
- **Regression tests added**
  (`enablements-core/src/test/java/com/acme/enablements/workflows/WorkerVersionEnablementWorkflowTest.java`)
  covering: exact order-count completion, the `deployWorkerVersion`
  off-by-one fix (signal no longer throws, uses signal fields), and
  `pause()` actually blocking submission until `resume()`. They use
  hand-written fakes for `OrderActivities`/`DeploymentActivities` rather
  than Mockito mocks, because Temporal's activity-registration validator
  rejects a Mockito-mocked activity interface (Byte Buddy copies the
  interface's `@ActivityMethod` annotations onto the mock's overriding
  methods, which Temporal's own validation then flags as invalid).

---

## Load-Generation Control Path Change (2026-09-15)

`enablements-api`'s `LoadGeneratorService`/`EnablementsController` no longer
start/signal the `WorkerVersionEnablement` workflow to run load generation.
They now start `OrderActivities.runSubmissionLoop` directly as a
[Standalone Activity](https://docs.temporal.io/standalone-activity) via
`ActivityClient`, observe it via `describe()`/heartbeat details, and cancel
it via `ActivityHandle.cancel()`. See spec.md's "Reconciliation Note
(2026-09-15)" for the full rationale.

- `WorkerVersionEnablementImpl`, `WorkerVersionEnablement`,
  `DeploymentActivities`/`DeploymentActivitiesImpl`, the `deployWorkerVersion`
  signal, `pause()`/`resume()`, and `WorkerVersionEnablementWorkflowTest` are
  **untouched** -- still present, still passing, still usable via
  `temporal workflow start` (`ENABLEMENT.md`) for the version-transition
  demo. They are simply no longer in the load-gen API/UI's call path.
- `OrderActivities`/`OrderActivitiesImpl` are **untouched** -- the same
  activity code and Worker registration run whether invoked from the
  workflow or as a Standalone Activity.
- `pause`/`resume` REST endpoints and UI controls are removed: Standalone
  Activity Pause/Unpause exist server-side but aren't exposed as Client SDK
  methods, so there's no clean way to wire them up. `stop` now calls
  `cancel()` (cooperative, matches `runSubmissionLoop`'s existing
  heartbeat-driven cancellation) instead of `terminate()`.
- New proto message `LoadGenerationState` (`enablement_id`, `status`,
  `orders_submitted_count`) replaces `WorkerVersionEnablementState` as this
  path's REST response shape; `WorkerVersionEnablementState` is unchanged and
  still serves the workflow path.
- Frontend (`web/src/lib/api/loadgen.ts`,
  `web/src/routes/demo/+page.svelte`, `web/src/lib/components/LoadShapePanel.svelte`):
  Pause/Resume controls removed; polling now stops on a terminal
  `ExecutionStatus` (`COMPLETED`/`CANCELED`/`FAILED`) instead of
  `DemoPhase.COMPLETE`; the "Temporal UI" link now points at the Standalone
  Activity, not a workflow.

---

## Revision History

| Date | Author | Change | Status |
|------|--------|--------|--------|
| 2026-03-18 | [Your Name] | Initial spec draft (workflow-based approach) | Draft |
| 2026-03-18 | [Your Name] | Clarified API paths: workflow activities call production APIs, enablements endpoints in separate controller | In Progress |
| 2026-03-18 | [Your Name] | **Major clarification:** Enablement workflow is external caller of OMS, doesn't duplicate OMS state tracking. Workflow owns execution state only, OMS owns order state. | In Progress |
| 2026-03-18 | [Your Name] | **Namespace correction:** Enablement workflows run in `apps` namespace (external caller), not `processing` namespace. Task queue: `enablements`. | In Progress |
| 2026-03-18 | [Your Name] | **Deployment model clarified:** Enablement runs locally on host (not in K8s). Calls OMS APIs in K8s/KinD. No K8s deployment manifests needed. | In Progress |
| 2026-09-14 | Temporal PSE Team | **Reconciliation:** corrected the spec to match the real (already-built) code, fixed four real bugs, and repointed order generation at the Commerce App/Payments Processor simulators from `SPECS/commerce-payments-apps/spec.md`, adding a weighted `scenario_weights` mix in place of the old `orderIdSeed`-string-match hack. | In Progress |
