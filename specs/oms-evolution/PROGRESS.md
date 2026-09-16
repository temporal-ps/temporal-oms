# OMS Evolution - Progress Tracking

**Initiative:** Represent every `apps.Order`/`processing.Order`/fulfillment version as real, coexisting code, and define OMS version as a named pin of one apps version + one processing version + one fulfillment version
**Status:** Draft - Ready for Review
**Owner:** Temporal FDE Team
**Created:** 2026-09-16

---

## Submission Checklist

- [x] Spec written (`spec.md`)
- [x] Goals & acceptance criteria defined
- [x] Component version ladder documented for all three bounded contexts, including fulfillment's "embedded in processing" stage before `fulfillment.Order` existed
- [x] OMS version mapping table (direct, unambiguous row-per-version)
- [x] Design decisions recorded, including rejected alternatives (file-suffix, single global version number, numbering from the workshop's existing v1/v2)
- [x] Implementation strategy broken into phases
- [x] Testing approach specified
- [ ] Tech lead review

---

## Open Items Before Approval

- [ ] Confirm package-per-version over file-suffix as the final convention
- [ ] Confirm whether `apps.Order` needs a future `v4`
- [ ] Confirm whether the Safe Fulfillment Handoff workshop renumbering (Phase 5) lands in the same
      change as the code restructuring or as an immediate follow-up
- [ ] Confirm why `@WorkflowVersioningBehavior(PINNED)` is commented out on `fulfillment.Order` today
      before building fulfillment v2 on top of it
- [ ] Confirm whether `k8s/base/fulfillment` gets its own `WorkerDeployment` CRD in Phase 4 or stays
      a plain `Deployment`

---

## Feedback Items

_(Tech lead feedback goes here after review.)_

---

## Next

Tech lead review of `spec.md`, focused on:
1. Whether package-per-version is worth the one-time restructuring cost over the existing file-suffix pattern
2. The OMS version mapping table: confirm it matches the intended rollout narrative from the Safe Fulfillment Handoff spec
3. Scope and timing of Phase 5 (workshop material renumbering)
4. Whether Phase 4 (OMS version selection wiring in `demo-up.sh`/`app-deploy.sh`) should be its own follow-on spec instead of folded in here
5. The fulfillment swimlane: "embedded in processing" to "v1 (unversioned fulfillment.Order)" to "v2 (versioned)", and whether it belongs in this spec's Phase 3 or a separate fulfillment-focused spec
