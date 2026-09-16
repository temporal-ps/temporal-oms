# Commerce App + Payments Processor - Progress Tracking

**Initiative:** Build stateful Commerce App and Payments Processor simulators inside `enablements`, publishing webhook events through a shared, generic publisher
**Status:** ✅ Phases 1-6 implemented (2026-09-14); see "Implementation Notes" for divergences from the draft design
**Owner:** Temporal FDE Team
**Created:** 2026-09-13

---

## Scope Note

This spec deliberately stops at "events publish correctly, inspectably."
Wiring `apps-api`'s `CommerceWebhookController`/`PaymentsWebhookController`
as real subscribers, which is what actually completes an order
end-to-end, is a separate, follow-on spec not yet written.

---

## Submission Checklist

- [x] Spec written (`spec.md`)
- [x] Goals & acceptance criteria defined
- [x] Architecture documented (Commerce App, Payments Processor, shared `WebhookPublisher`)
- [x] Design decisions recorded, including rejected alternatives (real Stripe, standalone new modules, one system-wide aggregating workflow)
- [x] Implementation strategy broken into phases
- [x] Testing approach specified
- [ ] Tech lead review

---

## Resolved (2026-09-13 discussion)

- [x] Auto-capture delay: superseded by a scenario-driven design, see below
- [x] Webhook delivery contract: nailed down now (Temporal activity retry:
      1s initial, 2.0 backoff, 30s max interval, 5 max attempts; applies
      only to sides actually attempted)
- [x] Naming collision: keep the existing `CommerceIntegrationService`
      name; new service is `CommerceAppBackendService`

## Design Change: Scenario-Driven, Scheduled Webhook Delivery

Significant addition beyond the original draft, based on further
discussion: webhook delivery is no longer fired inline from
`CommerceOrder`/`PaymentCharge`'s own state transitions. Instead:

- A `ScenarioOptions` selection (a floating UI control at checkout) picks
  one of four presets: `NORMAL`, `PAYMENT_BEFORE_COMMERCE`,
  `MISSING_COMMERCE_EVENT`, `MISSING_PAYMENT_EVENT`, letting the demo
  reproduce the v1 PRD's own stated integration failure modes on demand.
- A new `PendingPublishRegistry` singleton workflow persists placed
  orders awaiting delivery (Temporal-workflow-backed, not a database, per
  explicit user direction).
- A new `PublishCartOrders` workflow, run on a **Temporal Schedule every
  10 seconds** (this repo's first use of Temporal Schedules), applies each
  entry's scenario and performs delivery via `WebhookPublisher`.

See `spec.md`'s Architecture Overview, Component Design, and Data Model
sections for the full design.

## Reconciled With Load Generation (2026-09-14)

`SPECS/enablements/load-generation/spec.md` (the `WorkerVersionEnablement`
workflow, used for worker-version rollout testing) is now a second
consumer of this spec's Commerce App/Payments Processor REST surface,
replacing its old hand-rolled direct calls into `apps-api`. See that
spec's "Reconciliation Note" and "Fixes Applied in This Reconciliation"
sections. Cross-Cutting Concerns here notes that this second consumer
requires the webhook subscriber list to actually be populated, unlike the
general demo/UI path.

## Design Refinement: Authorize-vs-Capture (2026-09-14)

Per further discussion: `PaymentCharge` previously only mattered to order
completion on capture, with no mention of what happens at authorization.
This is now explicit:
- `PaymentCharge` publishes an inspection-only `payment.authorized` event
  immediately on authorization (no real subscriber consumes it yet), so a
  future OMS-side change to start processing at authorization needs no
  change here.
- New `CAPTURE_FAILED` terminal status, distinct from `VOIDED`: models a
  real payment-processor outcome (authorization hold expired, amount
  mismatch, issuer decline at capture time) where a successful
  authorization still fails to capture, per the v1 PRD's own
  "failure to receive a capture notification should cancel the order"
  rule. A new card-number test value triggers it.
- Documented as a deliberate simplification (Design Decisions), with the
  fuller "start order processing at authorization" design explicitly
  flagged as a question for the follow-on (OMS-side wiring) spec, not
  answered here.

## Open Items Before Approval

- [ ] Confirm `PublishCartOrders` tick interval default (proposed: 10s)
- [ ] Confirm `ScheduleOverlapPolicy` (proposed: `SKIP`)
- [ ] Decide whether permanently-withheld (`MISSING_*`) entries should
      ever be pruned from `PendingPublishRegistry`
- [ ] Confirm the 4-preset `DemoScenario` enum is sufficient for now
- [ ] Confirm whether the follow-on spec should have `apps-api` react to
      `payment.authorized` (start processing early) rather than only
      `payment.captured`

---

## Feedback Items

_(Tech lead feedback goes here after review.)_

---

## Implementation Notes (2026-09-14)

All six phases are implemented in `java/enablements/`, `proto/acme/enablements/domain/v1/`,
and `web/`. Points where the real implementation diverges from this
document, each a considered call made during implementation rather than a
silent reinterpretation:

- **Webhook recent-events log is Temporal-backed, not activity-local
  in-memory state.** The spec's `WebhookPublisher` design holds its
  recent-events ring buffer as in-process state inside the activity, but
  `enablements-api` (which serves `GET /webhooks/events`) and
  `enablements-workers` (which runs the `WebhookPublisher` activity) are
  separate deployed processes; an in-memory list in one JVM is invisible to
  the other. Added a new singleton `WebhookEventLog` workflow
  (`com.acme.enablements.webhooks.workflows`, continueAsNew-bounded, same
  shape as `PendingPublishRegistry`) that `WebhookPublisherImpl` appends to
  via `WorkflowClient`, and that `enablements-api`'s new
  `WebhookIntegrationService` queries. Confirmed with the user before
  building it.
- **`CommerceInventoryActivities` added** (`WorkflowClient`-backed caller,
  mirroring `PendingPublishRegistryActivities`) so `CommerceOrder` can call
  the `CommerceInventory` singleton: a workflow cannot call another
  workflow's update/query directly in-process, the same constraint that
  motivated `PendingPublishRegistryActivities` in the spec itself. Not in
  the spec's Critical Files list, but the same category of gap.
- **`registerPaymentAuthorization` added as a fourth `PendingPublishRegistry`
  update method.** The spec's Interfaces bullet lists only
  `registerCommerceOrder`/`registerPaymentCapture`/`markDelivered`, but the
  `PendingPublishEntry` record it defines has an `authorizedPayloadJson`
  field with no update method that could ever set it. Added the missing
  method; `PaymentCharge` calls it on reaching `AUTHORIZED`.
- **Commerce catalog fixture lives in `enablements-core`, not
  `enablements-api`.** `enablements-workers` (a separate deployable from
  `enablements-api`) must seed `CommerceInventory`'s initial stock at
  startup and does not depend on `enablements-api`, so the fixture and its
  loader (`CommerceCatalogFixtureService`) had to live in the shared `core`
  module both depend on. Matches how `acme.enablements.yaml` itself is
  already shared this way.
- **`PaymentCharge`'s auto-capture delay and `WebhookPublisher`'s retry
  policy are code constants, not values read from
  `acme.enablements.yaml` at runtime.** Workflow implementation classes are
  instantiated directly by the Temporal SDK (no Spring DI), so they cannot
  read `@Value`-injected config; only activities (Spring beans) can. Both
  values are hardcoded to match the YAML's documented defaults (5s delay,
  1s/2.0/30s/5-attempt retry) instead. The YAML entries stay as
  operator-facing documentation of the intended defaults; changing them
  requires editing the constant and redeploying, not just editing config.
- **Proto package is `acme.enablements.domain.enablements.v1`**, not
  `acme.enablements.domain.v1` as the spec's own snippets say: every other
  `domain/v1` proto in this repo doubles the context name in its package
  (e.g. `acme.fulfillment.domain.fulfillment.v1`), and the new files follow
  that real, verified convention over the spec's simplified example.
- **`java/generated`'s Java plugin pin added to `buf.gen.yaml`**
  (`buf.build/protocolbuffers/java:v34.1`, matching `protobuf.version` in
  `java/pom.xml`). It was unpinned before this change; regenerating with an
  unpinned remote plugin picked up a newer gencode than the pinned
  `protobuf-java` runtime and broke every existing generated class at
  class-init (`RuntimeVersion$ProtobufRuntimeVersionException`). Unrelated
  to this spec's own content but a real, repo-wide side effect of running
  `buf generate` here, fixed in the same pass.
- **`/shop/orders` shows the current single order/charge, not a list.**
  The spec explicitly scopes out a cross-order dashboard ("only
  single-entity status lookups... are built"); the pre-existing page
  listed all of a customer's orders via `apps-api`'s always-empty stub,
  which is now removed, so the page now looks up the last order/charge
  this browser created via `orderId`/new `chargeId` stores.
- **Checkout does not wire the `GET .../commerce/shipping/rates` quote
  into `selected_shipment`.** The endpoint exists and works
  (`CommerceAppBackendService.getShippingRates`, a simple deterministic
  standard/express quote); the Svelte checkout doesn't call it or let the
  customer pick a rate. Phase 5's bullet list doesn't name shipping-rate
  selection as a required UI element, so this was left out to bound scope.
- **Not implemented from the Testing Strategy:** the full enumerated test
  matrix. Added focused Temporal `TestWorkflowEnvironment` unit tests for
  `PaymentCharge` (all four card outcomes, void-before-capture),
  `CommerceInventory` (hold/release/over-hold), `PendingPublishRegistry`
  (register/mark-delivered/query filtering), and `PublishCartOrders` (all
  four `DemoScenario` presets plus the unconditional `payment.authorized`
  side), all in `enablements-core/src/test/java`. Did not add the
  Integration Tests section's items (a real local HTTP mock-server retry
  test for `WebhookPublisher`, or an end-to-end test driving
  `scripts/simulate-scenario.sh`).

---

## Next

Tech lead review of `spec.md` plus this Implementation Notes section,
focused on:
1. Whether folding inventory/shipping into the Commerce App (vs. separate
   simulated apps) is the right footprint trade-off
2. Whether the entity-workflow approach (`CommerceOrder`, `CommerceInventory`,
   `PaymentCharge`, `PendingPublishRegistry`, modeled on `SupportTeam`) is
   the right backing store vs. a lighter-weight alternative
3. The scenario-driven, Schedule-based delivery design (this repo's
   first use of Temporal Schedules) and the four open items above
4. Scope boundary with the not-yet-written follow-on spec (OMS-side wiring)
5. The divergences recorded in "Implementation Notes" above, especially the
   `WebhookEventLog` addition and the `PendingPublishRegistry` interface gap
