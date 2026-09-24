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

## Implementation Status (2026-09-17)

### spec.md Phase 1-3: package-per-version restructuring: done

- `com.acme.apps.workflows.{v1,v2,v3}.OrderImpl` created. `v1` is a new baseline
  (no Worker Versioning, delegate-only). `v2` is renumbered from `OrderImplV1.java`
  (Worker Versioning on, delegate-only, workshop live-edit starting point). `v3` is
  renumbered from the old default `OrderImpl.java` (owns fulfillment via Nexus).
- `com.acme.processing.workflows.{v1,v2,v3,v4}.OrderImpl` created. `v1` is a new
  baseline (no Worker Versioning, always-Kafka). `v2` is renumbered from
  `OrderImplV1.java` (Worker Versioning on, always-Kafka, workshop live-edit starting
  point). `v3` is renumbered from the old default `OrderImpl.java`
  (`send_fulfillment` guard). `v4` is new: Kafka activity call and `Fulfillments`
  activity stub removed entirely.
- `com.acme.fulfillment.workflows.{v1,v2}.OrderImpl` created. `v1` is renumbered from
  the old unversioned `OrderImpl.java`, unchanged (still not Worker-Versioned). `v2`
  is new: `@WorkflowVersioningBehavior(PINNED)` uncommented.
- `acme.apps.yaml` / `acme.processing.yaml` default workflow-class now point at
  `v3.OrderImpl` (same code, new path, no behavior change).
- `acme.fulfillment.yaml` gained `deployment-properties` (`use-versioning: true`,
  `default-versioning-behavior: PINNED`) and an
  `${ACME_FULFILLMENT_ORDER_WORKFLOW_CLASS:...}` override, matching the apps/processing
  pattern. Default value is `v1.OrderImpl` to preserve current behavior; an operator
  opts into `v2` via the env var override, the same way apps/processing versions are
  selected today.
- Verified via `mvn -DskipTests install` from `java/`: builds clean.

### spec.md Phase 4: OMS version selection wiring: not started

`OMS_VERSION` resolution and `scripts/kind|k3d/app-deploy.sh` wiring is not yet done.
Left for a follow-up pass; out of scope for this dispatch.

### spec.md Phase 5: Safe Fulfillment Handoff workshop renumbering: done

`README.md`, `SOLUTION.md`, and `scripts/*.sh` updated from the workshop's old
numbering ("v1"/"v2") to the new one ("v2"/"v3"): headings, build-id values,
`ACME_*_ORDER_WORKFLOW_CLASS` overrides, and file-path links to the renumbered
`v2/OrderImpl.java` files. Part 2's own Kubernetes image-tag rollout sequence
(`:v1`/`:v2` via `VERSION=` on `deploy-processing-workers.sh`) is left as-is: it is a
free-standing docker-tag convention for that demo, decoupled from the Java
package-per-version numbering (confirmed by reading the deploy script itself).

### hosting.md Mode B: on-demand rollout control: done

- `DeploymentActivitiesImpl.deployWorkerVersion` now resolves the target workflow
  class from `{deployment_name}/{version}` using spec.md's package-per-version
  convention, applies a generalized Deployment/Service template
  (`k8s/base/templates/worker-deployment-template.yaml`) for apps/fulfillment (plain
  Deployments), and patches the existing `k8s/processing-versioned` WorkerDeployment
  CRD in place for processing. It then retries `temporal worker deployment
  set-current-version` (matching `_lib.sh`'s retry pattern), confirms with `describe`,
  and returns both in `DeployWorkerVersionResponse`. The old `registerCompatibility()`
  (deprecated `worker-build-id update-compatibility` call, hardcoded to v1/v2) is
  removed; its corrected logic is folded into `deployWorkerVersion` since the new REST
  path calls only that one activity method.
- `WorkerVersionEnablementImpl` updated to drop its now-redundant second call; its
  combined load+deploy orchestration is otherwise untouched (per hosting.md, not the
  near-term reuse target).
- New `POST /api/v1/enablements/deployments/{boundedContext}` endpoint
  (`DeploymentsController` + `DeploymentService`), invoking `deployWorkerVersion` as a
  standalone activity via `ActivityClient`, the same pattern `LoadGeneratorService`
  uses. Does not touch the existing load-gen endpoints or workflow.
- New Admin page `web/src/routes/admin/worker-versions/+page.svelte`: independent load
  panel (reuses the existing load-gen endpoints/store as-is) and a per-bounded-context
  deployment panel (target-version input, promote button, last result). Both link out
  to the Temporal UI rather than re-deriving current build-id state locally, per
  hosting.md's design.
- Verified via `mvn -DskipTests install` (java/), the existing
  `WorkerVersionEnablementWorkflowTest` suite, and `npm run check` + `npm run build`
  (web/).

---

## Next

Tech lead review of `spec.md`, focused on:
1. Whether package-per-version is worth the one-time restructuring cost over the existing file-suffix pattern
2. The OMS version mapping table: confirm it matches the intended rollout narrative from the Safe Fulfillment Handoff spec
3. Scope and timing of Phase 5 (workshop material renumbering)
4. Whether Phase 4 (OMS version selection wiring in `demo-up.sh`/`app-deploy.sh`) should be its own follow-on spec instead of folded in here
5. The fulfillment swimlane: "embedded in processing" to "v1 (unversioned fulfillment.Order)" to "v2 (versioned)", and whether it belongs in this spec's Phase 3 or a separate fulfillment-focused spec
