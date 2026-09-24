# Worker Versioning Enablements Specification

**Feature Name:** Worker Version Deployment with Build-ID Routing
**Status:** Superseded by [`SPECS/oms-evolution/hosting.md`](../../oms-evolution/hosting.md) (Mode B: Sequential On-Demand Rollout), 2026-09-17
**Owner:** [Your Name]
**Created:** 2026-03-18
**Updated:** 2026-03-18

**Part of:** [Worker Version Enablement Initiative](../INDEX.md)
**Depends on:** [Worker Version Enablement Workflow](../load-generation/spec.md)

> This spec was written against Temporal's deprecated build-id-compatibility API
> (`updateWorkerBuildIdCompatibility` / `temporal worker-build-id update-compatibility`), before this
> repo standardized on the modern Worker Deployment API (`temporal worker deployment
> set-current-version`, `k8s/processing-versioned`'s `WorkerDeployment` CRD). Its ideas carry forward
> into `hosting.md`'s Mode B on the modern API: build-id-based routing, canary/gradual traffic shift
> (now ramp-percentage, deferred there for a first pass), rollback (the same promote operation with
> an older build-id), and its version-transition test scenario, all reconciled there so nothing here
> is lost, just re-based on the API this repo actually uses. Kept as historical reference; do not
> implement further against this document.

---

## Overview

### Executive Summary

Configure Temporal worker versioning infrastructure that enables safe deployment of new worker versions alongside existing ones. Using Temporal's build-id routing, we'll deploy processing-worker-v2 alongside v1, allowing:

- New workflows automatically use v2
- Existing workflows continue on v1 until completion
- Zero forced migrations or workflow failures
- Gradual traffic shift as v1 workflows complete

This establishes the pattern for production upgrades without downtime.

---

## Goals & Success Criteria

### Primary Goals
- Goal 1: Configure workers with build-id versioning (v1, v2)
- Goal 2: Deploy two worker versions in Kubernetes
- Goal 3: New workflows route to v2, old workflows stay on v1
- Goal 4: Verify no workflow failures during version transition
- Goal 5: Document version transition runbook for team

### Acceptance Criteria
- [ ] processing-worker-v1 deployment with build-id: `processing-worker:v1`
- [ ] processing-worker-v2 deployment with build-id: `processing-worker:v2`
- [ ] Temporal configured to support build-id task routing
- [ ] New workflows created during v2 deployment use v2
- [ ] Existing workflows complete successfully on v1
- [ ] Metrics show version distribution (% v1 vs v2 by workflow)
- [ ] Runbook documents version transition steps

---

## Current State (As-Is)

### What exists today?
- **Single worker version:** `processing-worker` with static build-id `local-dev`
- **No worker versioning:** All workers treated identically, no version routing
- **No canary deployments:** Can't test new code without affecting all workers
- **All-or-nothing upgrades:** Must stop all workers, update code, restart (downtime)

### Pain points / gaps
- Can't validate new code on subset of workflows
- Workflow failures during upgrades cause cascading issues
- No way to roll back (must revert code and restart)
- Unknown compatibility gaps until production (too late)
- Difficult to understand which worker version handles which workflow

---

## Desired State (To-Be)

### Architecture Overview

```
┌────────────────────────────────────────────────────────────┐
│                 Temporal Cluster                           │
│                                                            │
│  Task Queue: "processing"                                │
│  ┌──────────────────────────────────────────────────────┐ │
│  │  Workflow ID Range 1-50                             │ │
│  │  ├─ Order-001: build-id=processing-worker:v1       │ │
│  │  ├─ Order-002: build-id=processing-worker:v1       │ │
│  │  ├─ Order-051: build-id=processing-worker:v2       │ │
│  │  └─ Order-052: build-id=processing-worker:v2       │ │
│  └──────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────┘
         ↓ routes based on build-id ↓

┌──────────────────────────┐   ┌──────────────────────────┐
│  processing-worker-v1    │   │  processing-worker-v2    │
│  (1 replica)             │   │  (1 replica)             │
│  build-id: v1            │   │  build-id: v2            │
│  Version: 1.0.0          │   │  Version: 1.1.0          │
│                          │   │  (canary)                │
│  Handles:                │   │  Handles:                │
│  • Orders 1-50           │   │  • Orders 51+            │
│  • Existing workflows    │   │  • New workflows         │
└──────────────────────────┘   └──────────────────────────┘
```

### Key Capabilities
- **Build-ID Routing:** Temporal routes tasks to workers by build-id match
- **Compatibility Declaration:** Explicitly mark versions compatible/incompatible
- **Canary Deployments:** Run v2 with small replica count while v1 handles main load
- **Version Tracking:** Query workflows to see which version handles each
- **Graceful Transition:** v1 workflows complete naturally, new → v2
- **Automatic Rollback:** Mark v2 incompatible if issues detected, stop routing to it

---

## Technical Approach

### Design Decisions

| Decision | Rationale | Alternative Considered |
|----------|-----------|------------------------|
| Use Temporal build-id (not semantic versions) | Temporal's native task routing mechanism; explicit control | Custom version logic - reinvents Temporal wheel |
| Separate K8s deployment per version | Clear version isolation; easy to scale/rollback per version | Single deployment with multiple replicas - harder to manage versions |
| Canary: v2 with 1 replica vs v1 with 1 | Start small, validate v2 before scaling; gradual traffic shift | 50/50 split - too much risk on unproven v2 |
| Mark versions compatible in Temporal | Default behavior: old versions incompatible with new (safe) | Assume compatibility - risky, can cause failures |
| Document manual transition steps | Enables repeatable, observable process; team learns the pattern | Fully automated - can hide issues, team doesn't understand mechanism |

### Component Design

#### Worker V1 Deployment (Existing - Enhanced)
- **Build-ID:** `processing-worker:v1`
- **Version:** Current code (e.g., 1.0.0)
- **Replicas:** 1 (reduced from 2 for headroom)
- **Configuration:**
  ```java
  WorkerOptions.newBuilder()
    .setBuildId("processing-worker:v1")
    .useWorkerVersioning(true)
    .setVersioningIntent(VersioningIntent.COMPATIBLE)
    .build()
  ```
- **Env vars:**
  ```
  BUILD_ID=processing-worker:v1
  USE_WORKER_VERSIONING=true
  ```

#### Worker V2 Deployment (New - Canary)
- **Build-ID:** `processing-worker:v2`
- **Version:** New code (e.g., 1.1.0 - can include schema changes, logic improvements)
- **Replicas:** 1 (initial canary)
- **Configuration:** Same as v1, but with `setBuildId("processing-worker:v2")`
- **Env vars:** Same as v1, but `BUILD_ID=processing-worker:v2`
- **Kubernetes:** New deployment `processing-workers-v2` in same namespace

#### Temporal Compatibility Configuration
- **Initial state (v1 only):**
  ```
  No compatibility rules (single version)
  ```
- **After v2 deployed:**
  ```
  processing-worker:v1 (default)
    └─ compatible with: [v2] (explicit declaration)

  processing-worker:v2
    └─ compatible with: [] (no prior versions)
  ```
- **If v2 has issues:**
  ```
  Mark v2 incompatible, remove from routing
  Processing stops using v2, falls back to v1
  ```

### Workflow Compatibility Model

**Forward Compatibility Check:**
```
Can v2 workers execute workflows started by v1?

✓ YES if:
  • Workflow interface unchanged (same update/signal methods)
  • State mutations backward-compatible
  • No removed fields from workflow state

✗ NO if:
  • Breaking changes to workflow interface
  • Schema changes that break existing state
  • Activity signatures changed (old tasks incompatible)
```

**For this demo:** v2 will be compatible (same workflow interface, maybe different activity implementation).

### Configuration & Deployment

**Build-ID Environment Variable:**
```yaml
# processing-workers deployment
env:
- name: BUILD_ID
  value: processing-worker:v1
- name: USE_WORKER_VERSIONING
  value: "true"

# processing-workers-v2 deployment
env:
- name: BUILD_ID
  value: processing-worker:v2
- name: USE_WORKER_VERSIONING
  value: "true"
```

**Kubernetes Deployment (via Temporal Worker Controller):**

We use the Temporal Worker Controller (temporalio/temporal-worker-controller) with WorkerDeployment CRD for versioned worker management.

```yaml
# k8s/base/processing/temporal-worker-deployment-v1.yaml
apiVersion: workload.temporal.io/v1
kind: WorkerDeployment
metadata:
  name: processing-workers-v1
  namespace: temporal-oms-processing
spec:
  workload:
    buildId: processing-worker:v1
    replicaCount: 1
    # ... (container spec with processing-workers image)

# k8s/base/processing/temporal-worker-deployment-v2.yaml (new for v2)
apiVersion: workload.temporal.io/v1
kind: WorkerDeployment
metadata:
  name: processing-workers-v2
  namespace: temporal-oms-processing
spec:
  workload:
    buildId: processing-worker:v2
    replicaCount: 1
    # ... (same as v1, but buildId=processing-worker:v2)
```

**Note:** The Temporal Worker Controller manages the actual Pods and handles build-id registration automatically. This simplifies version management compared to manual Deployment objects.

**Temporal Compatibility Registration:**
Java code in worker initialization:
```java
// In WorkerVersioningSetup class or worker factory

WorkflowClient client = WorkflowClient.newInstance(options);

// Register v1 as compatible with v2
client.getWorkflowServiceStubs().updateWorkerBuildIdCompatibility(
  UpdateWorkerBuildIdCompatibilityRequest.newBuilder()
    .setNamespace("processing")
    .setBuildId("processing-worker:v1")
    .setCompatibleBuildId("processing-worker:v2")
    .build()
);
```

Or via Temporal CLI (manual):
```bash
temporal worker-build-id update-compatibility \
  --build-id processing-worker:v1 \
  --compatible-with processing-worker:v2 \
  --namespace fde-oms-processing
```

---

## Implementation Strategy

### Phase 1: Prepare V1 for Versioning
**Goal:** Make existing workers version-aware

Deliverables:
- [ ] Update `processing-workers/pom.xml` (no changes, already has SDK)
- [ ] Add BUILD_ID env var to deployment
- [ ] Add USE_WORKER_VERSIONING env var
- [ ] Create `WorkerVersioningSetup.java` class
- [ ] Register v1 build-id with Temporal
- [ ] Test: v1 workers start with build-id configured

### Phase 2: Create V2 Deployment
**Goal:** Deploy v2 workers as canary

Deliverables:
- [ ] Create `k8s/base/processing/deployment-workers-v2.yaml`
- [ ] New processing-workers-v2 Java process/image (or reuse v1 image with different BUILD_ID)
- [ ] Register v2 build-id with Temporal
- [ ] Update `k8s/base/kustomization.yaml` to include v2 deployment
- [ ] Test: v2 workers start and register build-id

### Phase 3: Configure Temporal Compatibility
**Goal:** Tell Temporal to route new tasks to v2

Deliverables:
- [ ] Call `updateWorkerBuildIdCompatibility` during startup
- [ ] Verify Temporal CLI shows compatibility rules
- [ ] Test: New workflows route to v2, old stay on v1 (via logs/metrics)

### Phase 4: Transition Monitoring & Runbook
**Goal:** Document the process for team

Deliverables:
- [ ] Runbook: `docs/worker-versioning-runbook.md`
  - Step-by-step version transition procedure
  - Monitoring during transition
  - Rollback procedure
  - Troubleshooting guide
- [ ] Metrics dashboard: Show version distribution over time
- [ ] Health checks: Verify no workflow failures
- [ ] Demo script: Automated version transition walkthrough

### Critical Files / Modules

**To Create:**
- `java/processing/processing-workers/src/main/java/com/acme/processing/workers/WorkerVersioningSetup.java`
- `k8s/base/processing/deployment-workers-v2.yaml`
- `docs/worker-versioning-runbook.md`
- `scripts/version-transition-demo.sh`

**To Modify:**
- `java/processing/processing-workers/src/main/resources/application.yaml` - Add BUILD_ID env binding
- `k8s/base/processing/deployment-workers.yaml` - Rename to deployment-workers-v1.yaml, add BUILD_ID env
- `k8s/base/kustomization.yaml` - Add deployment-workers-v2.yaml
- `scripts/kind/app-deploy.sh` and `scripts/k3d/app-deploy.sh` - Update to deploy both v1 and v2

---

## Testing Strategy

### Unit Tests
- WorkerVersioningSetup correctly reads BUILD_ID from environment
- Temporal WorkerOptions built with correct build-id
- Compatibility registration call formats correctly

### Integration Tests
- V1 and V2 workers both start successfully
- Both register build-ids with Temporal
- Temporal CLI shows both versions registered
- Query workflows to verify version assignment

### Version Transition Test

**Test: New workflows use v2, old use v1**
1. Start v1 only (current state)
2. Submit 10 orders via load generator (all go to v1)
3. Deploy v2 workers
4. Register v2 build-id with Temporal
4. Verify v1 marked compatible with v2
5. Submit 10 new orders (should go to v2)
6. Query workflows:
   - Orders 1-10: build-id = v1
   - Orders 11-20: build-id = v2
7. Verify both groups complete successfully

**Acceptance Criteria:**
- [ ] V1 and v2 workers coexist without conflicts
- [ ] New workflows route to v2 (verified via Temporal UI)
- [ ] Old workflows stay on v1 (verified via Temporal UI)
- [ ] All workflows (v1 and v2) complete successfully
- [ ] No errors in worker logs during transition
- [ ] Metrics show correct version distribution

---

## Risks & Mitigation

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|-----------|
| V2 has breaking changes, v1 workflows fail when routed to v2 | High | Medium | Test schema compatibility offline; write migration tests; mark v2 incompatible if issues detected |
| Temporal compatibility registration fails silently | Medium | Low | Verify via Temporal CLI after registration; check build-id list |
| Workers with different versions cause task conflicts | Medium | Low | Build-id routing is explicit; Temporal only sends tasks to matching version |
| V2 workers crash, workflows get stuck | High | Low | Monitor worker health checks; have rollback procedure ready (mark v2 incompatible) |
| Version transition takes too long (SLA violated) | Medium | Medium | Set timeout for transition (e.g., 30 min max); have manual rollback |

---

## Dependencies

### External Dependencies
- Temporal 1.33+ (build-id support)
- Temporal Worker Controller v1.7.0+ (temporalio/temporal-worker-controller; Helm chart 0.26.0+) - for managing WorkerDeployment CRD
- Kubernetes 1.24+

### Cross-Cutting Concerns
- **Workflow definitions:** Must be forward-compatible for v2
- **Task queue:** Must be same for both versions (routing depends on it)
- **Load generator:** Needed for realistic test scenarios (from load-generation spec)
- **Validation framework:** Needed to verify zero failures (from validation-framework spec)

### Rollout Blockers
- [ ] Temporal Worker Controller deployed (v1.7.0+ / Helm chart 0.26.0+) with WorkerDeployment CRD
- [ ] Temporal cluster supports build-ids (verify version 1.33+)
- [ ] Processing workflows forward-compatible with v2
- [ ] Enablement workflow deployment ready (needed for test scenarios)

---

## Open Questions & Notes

### Questions for Tech Lead / Product

- [ ] Should v2 code be different (features/fixes) or just version bump for testing?
- [ ] How to handle long-running workflows (>1 hour)? Keep on v1 until completion?
- [ ] If v2 has minor bugs, should we mark incompatible or fix and redeploy?
- [ ] Document runbook for prod team or just engineering?

### Implementation Notes

- **Build-ID versioning:** Not required for all workers; optional per team
- **Compatibility model:** Conservative default (old incompatible with new)
- **Graceful degradation:** If v2 issues detected, can immediately stop routing to it
- **Monitoring:** Track workflow versions in metrics for observability
- **Runbook updates:** After first transition, team can improve the process

---

## References & Links

- [Worker Version Enablement Initiative](../INDEX.md)
- [Worker Version Enablement Workflow](../load-generation/spec.md)
- [Validation Framework](../validation-framework/spec.md)
- [Temporal Build-ID Docs](https://temporal.io/blog/worker-versioning)
- [Temporal CLI Worker Build-ID](https://docs.temporal.io/cli/worker/build-id)
