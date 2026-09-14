# Commerce App + Payments Processor - Progress Tracking

**Initiative:** Build stateful Commerce App and Payments Processor simulators inside `enablements`, publishing webhook events through a shared, generic publisher
**Status:** 📋 Draft - Ready for tech lead review
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

## Next

Tech lead review of `spec.md`, focused on:
1. Whether folding inventory/shipping into the Commerce App (vs. separate
   simulated apps) is the right footprint trade-off
2. Whether the entity-workflow approach (`CommerceOrder`, `CommerceInventory`,
   `PaymentCharge`, `PendingPublishRegistry`, modeled on `SupportTeam`) is
   the right backing store vs. a lighter-weight alternative
3. The scenario-driven, Schedule-based delivery design (this repo's
   first use of Temporal Schedules) and the four open items above
4. Scope boundary with the not-yet-written follow-on spec (OMS-side wiring)
