# Worker Version Enablement Workflow - Progress Tracking

**Spec:** [spec.md](./spec.md)
**Status:** 📋 Draft - Ready for Tech Lead Review (API paths clarified)
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
| Phase 1: Proto + Workflow | 🔶 Partially implemented, with known bugs (see below); needs the `scenario_weights` field and the four fixes | TBD |
| Phase 2: EnablementsWorkers | 🔶 Partially implemented (`OrderActivitiesImpl` calls `apps-api` directly today; needs to be repointed at the Commerce App/Payments Processor backends) | TBD |
| Phase 3: EnablementsController | ⏳ Not started | TBD |
| Phase 4: V2 + Demo scripts | 🔶 `ENABLEMENT.md` runbook exists and was updated for `scenario_weights`; formal demo scripts (`scripts/start-enablement-workflow.sh` etc.) not started | TBD |

### Known bugs in the existing implementation (to fix as part of this reconciliation)
- [ ] `execute()`'s deploy-request replay loop off-by-one throws `IndexOutOfBoundsException` on any `deployWorkerVersion` signal (`WorkerVersionEnablementImpl.java:86-91`)
- [ ] `pause()`/`resume()` signals are no-ops; they don't gate the submission loop (`WorkerVersionEnablementImpl.java:102-110`)
- [ ] `DeploymentActivitiesImpl.deployWorkerVersion` hardcodes `v2`/replicas=1, ignoring the signal's real fields (`DeploymentActivitiesImpl.java:37-50`)
- [ ] No `continueAsNew`; workflow blocks forever via `Workflow.await(() -> false)` (`WorkerVersionEnablementImpl.java:93-94`)
- [ ] `OrderActivitiesImpl.submitOrders` PUTs/POSTs directly into `apps-api`'s real webhooks instead of going through the Commerce App/Payments Processor simulators; its only "scenario" is a crude `orderIdSeed.contains("invalid")` string match

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

## Revision History

| Date | Author | Change | Status |
|------|--------|--------|--------|
| 2026-03-18 | [Your Name] | Initial spec draft (workflow-based approach) | Draft |
| 2026-03-18 | [Your Name] | Clarified API paths: workflow activities call production APIs, enablements endpoints in separate controller | In Progress |
| 2026-03-18 | [Your Name] | **Major clarification:** Enablement workflow is external caller of OMS, doesn't duplicate OMS state tracking. Workflow owns execution state only, OMS owns order state. | In Progress |
| 2026-03-18 | [Your Name] | **Namespace correction:** Enablement workflows run in `apps` namespace (external caller), not `processing` namespace. Task queue: `enablements`. | In Progress |
| 2026-03-18 | [Your Name] | **Deployment model clarified:** Enablement runs locally on host (not in K8s). Calls OMS APIs in K8s/KinD. No K8s deployment manifests needed. | In Progress |
| 2026-09-14 | Temporal PSE Team | **Reconciliation:** corrected the spec to match the real (already-built) code, fixed four real bugs, and repointed order generation at the Commerce App/Payments Processor simulators from `SPECS/commerce-payments-apps/spec.md`, adding a weighted `scenario_weights` mix in place of the old `orderIdSeed`-string-match hack. | In Progress |
