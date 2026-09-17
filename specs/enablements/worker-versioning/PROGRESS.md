# Worker Version Deployment - Progress Tracking

**Spec:** [spec.md](./spec.md)
**Status:** Superseded by [`SPECS/oms-evolution/hosting.md`](../../oms-evolution/hosting.md) (Mode B), 2026-09-17
**Owner:** [Your Name]
**Initiative:** [Worker Version Enablement](../INDEX.md)
**Depends on:** [Load Generation Service](../load-generation/PROGRESS.md)

---

## Current Status

| Component | Status | Owner |
|-----------|--------|-------|
| Spec document | ✅ Complete | [Your Name] |
| Tech lead review | ⏳ Awaiting | [Tech Lead] |
| Implementation planning | ⏳ Blocked (pending approval) | TBD |
| Development | ⏳ Not started | TBD |

---

## Spec Completion Checklist ✅

- [x] Executive summary written
- [x] Goals & acceptance criteria defined
- [x] Current state analysis
- [x] Desired state architecture
- [x] Technical design (deployments, configuration, compatibility model)
- [x] Implementation phases (4 phases with deliverables)
- [x] Testing strategy (unit, integration, transition test)
- [x] Risk assessment
- [x] Dependencies clearly documented

---

## Open Items Before Approval

### Questions Requiring Tech Lead Input

- [ ] V2 code changes: Features/fixes or just version bump?
- [ ] Long-running workflows (>1 hour): Keep on v1 or allow v2?
- [ ] Incompatible versions: Mark incompatible or redeploy fix?
- [ ] Runbook audience: Prod team, engineering, or both?

### Design Decisions to Validate

- [ ] Separate deployment per version vs single deployment with env var
- [ ] Canary strategy: 1 replica v2 vs 50/50 split (proposed: 1 replica)
- [ ] Manual Temporal registration vs automated during startup

---

## Tech Lead Review

### Submission Checklist
- [x] Spec is self-contained
- [x] Goals are measurable
- [x] Acceptance criteria are testable
- [x] Design decisions documented
- [x] Implementation is appropriately scoped
- [x] Risks identified and mitigated
- [x] Dependency on load-generation spec noted

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
   - Break 4 phases into detailed tasks
   - Estimate effort per phase
   - Identify dependencies (load-generation must be done first)
   - Create task list

2. **Phase 1: Prepare V1**
   - Add BUILD_ID env to current deployment
   - Create WorkerVersioningSetup.java
   - Register v1 with Temporal

3. **Phase 2: Create V2**
   - New K8s deployment (processing-workers-v2)
   - New Java image or config variant
   - Register v2 with Temporal

4. **Phase 3: Configure Compatibility**
   - Register compatibility rules
   - Verify via Temporal CLI

5. **Phase 4: Runbook & Monitoring**
   - Document transition steps
   - Create demo script
   - Set up metrics/dashboards

---

## Timeline Estimate

(To be refined after planning)

| Phase | Estimate | Depends On | Owner | Status |
|-------|----------|-----------|-------|--------|
| Planning | 1 day | Load gen approval | [TBD] | ⏳ Blocked |
| Phase 1: Prepare V1 | 1 day | Load gen done | [TBD] | ⏳ Blocked |
| Phase 2: Create V2 | 2 days | Phase 1 done | [TBD] | ⏳ Blocked |
| Phase 3: Compatibility | 1 day | Phase 2 done | [TBD] | ⏳ Blocked |
| Phase 4: Runbook | 2 days | Phase 3 done | [TBD] | ⏳ Blocked |
| **Total** | **~7 days** | | | |

---

## Dependencies

### Blocks This Sub-Spec
- [Validation Framework spec](../validation-framework/PROGRESS.md) (validation needs working versions)

### Blocked By
- ⏳ Tech lead approval
- ⏳ Load Generation spec approval + Phase 1-2 completion (needs load for testing)

### External Dependencies
- ✅ Temporal cluster (must support build-ids)
- ✅ Processing workflows (must be forward-compatible)
- ✅ KinD cluster

---

## Notes for Tech Lead

**Why This Spec Second?**
Version deployment depends on load generation (needs realistic test load during transition). This spec can be reviewed in parallel with load-gen but implemented after.

**What About V2 Code?**
This spec doesn't mandate V2 changes. V2 could be:
- Identical code (for testing versioning mechanism)
- Bug fixes (backwards-compatible)
- New features (backwards-compatible)

For the demo, **recommend keeping V2 identical to V1** to isolate versioning variables.

**Prod Readiness:**
This pattern (build-id routing, canary deployment) is production-ready. After demo, team can use it for real upgrades.

---

## Revision History

| Date | Author | Change | Status |
|------|--------|--------|--------|
| 2026-03-18 | [Your Name] | Initial spec draft | Draft |
