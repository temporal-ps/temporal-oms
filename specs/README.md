# Specifications Directory

This directory contains architectural specifications for major features and initiatives. Specs drive design alignment before implementation begins.

## Workflow

```
1. DRAFT          → Author writes spec using TEMPLATE.md
2. TECH LEAD      → Tech lead reviews spec, provides feedback
3. APPROVED       → Spec approved with guidance on trade-offs
4. PLANNING       → Implementation team breaks into detailed tasks
5. IMPLEMENTING   → Work proceeds in phases per implementation strategy
6. VALIDATING     → Verify against acceptance criteria
7. COMPLETE       → Feature shipped, lessons captured
```

## Directory Structure

```
SPECS/
├── TEMPLATE.md                           # Copy this to create new specs
├── README.md                             # This file
└── feature-name/                         # One dir per feature
    ├── spec.md                           # Main specification document
    ├── PROGRESS.md                       # Status tracking + checklist
    └── [optional artifacts]              # Diagrams, examples, design docs
```

## Creating a New Spec

1. **Copy the template:**
   ```bash
   mkdir -p SPECS/my-feature-name
   cp SPECS/TEMPLATE.md SPECS/my-feature-name/spec.md
   cp SPECS/TEMPLATE-PROGRESS.md SPECS/my-feature-name/PROGRESS.md  # [when available]
   ```

2. **Fill in each section:**
   - Start with Executive Summary (2-3 paragraphs)
   - Define Goals & Acceptance Criteria
   - Document Current & Desired state
   - Propose Technical Approach (decisions + design)
   - Break into Implementation Phases
   - Cover Testing Strategy
   - List Risks & Mitigations
   - Note Open Questions for Tech Lead

3. **Create PROGRESS.md:**
   - Track spec review status
   - List open items before approval
   - Prepare for tech lead review meeting

4. **Request review:**
   ```bash
   # Signal to tech lead that spec is ready
   # Create a PR or schedule a sync
   ```

## Spec Checklist (For Author)

Before requesting tech lead review, ensure:

- [ ] Executive summary is clear (non-technical person can understand)
- [ ] Goals are measurable and specific
- [ ] Acceptance criteria are testable (yes/no, not vague)
- [ ] Design decisions explain the "why" not just the "what"
- [ ] Alternatives considered are documented
- [ ] Implementation phases are realistic (not too big)
- [ ] Testing strategy covers happy path + edge cases
- [ ] Risks are identified (no "this will definitely work")
- [ ] Open questions are explicit (don't hide unknowns)
- [ ] No forward references (explain concepts as you introduce them)

## Tech Lead Review Checklist

When reviewing a spec:

1. **Clarity:** Can I understand the feature goal without asking?
2. **Scope:** Is this appropriately sized? (not a 1-person-year effort)
3. **Risks:** Are major risks identified? Are mitigations sound?
4. **Dependencies:** Will this block other teams? Does it need other work first?
5. **Testing:** Can we validate this objectively?
6. **Architecture:** Is the design sound? Any red flags?
7. **Trade-offs:** Are we making intentional choices or drifting?

Provide feedback in PROGRESS.md → Feedback Items section.

## Current Specs

### Workshop: Augment with AI
- **Status:** Draft
- **Goal:** Codespaces-based workshop teaching safe traffic migration (Kafka → Nexus) and AI-augmented Temporal workflows; exercise series for teams evaluating the new architecture
- **Owner:** Temporal FDE Team
- **Spec:** `specs/workshop/augment-with-ai/`
- **Exercise Specs:** `specs/workshop/safe-fulfillment-handoff/`, `specs/workshop/observe-shipping-agent/`
- **Next:** Tech lead review - confirm Exercise 03 scope, API key requirements, workshop startup runner, and Exercise 02 live LLM fallback

### Workshop: Codespaces Support
- **Status:** Devcontainer implemented; startup runner validation pending
- **Goal:** Define the Codespaces architecture, sizing, startup model, caching, risks, and validation plan for the Replay 2026 Temporal OMS workshop
- **Owner:** Temporal FDE Team
- **Spec:** `specs/workshop/codespaces-support/`
- **Next:** Implement/validate the workshop startup runner and local-process startup in a fresh Codespace

### Workshop: Integration Stubs
- **Status:** Runtime implementation complete; workshop material follow-up
- **Goal:** Document `enablements-api` as the workshop-owned integration fixture boundary for commerce-app, PIMS, inventory, shipping, and location-events
- **Owner:** Temporal FDE Team
- **Spec:** `specs/workshop/integrations/`
- **Next:** Complete Phase 8 workshop material and fixture inspection examples

### 🆕 fulfillment.Order (initiative)

#### fulfillment.Order Workflow
- **Status:** Implementing
- **Goal:** Replace Kafka fulfillment path with durable Temporal workflow; add address validation, inventory holds, versioned shipping, delivery tracking
- **Owner:** Temporal FDE Team
- **Spec:** `specs/fulfillment-order/fulfillment-order-workflow/`
- **Next:** Phase 1 (proto) is unblocked; 4 open questions to resolve before Phases 3/5/6 can complete (see PROGRESS.md)

#### ShippingAgent
- **Status:** Implemented for fixture-backed workshop path
- **Goal:** LLM-driven shipping advisor (Claude + tool activities) called by `fulfillment.Order` via Nexus; shipping and first-pass location integrations are backed by `enablements-api`
- **Owner:** Temporal FDE Team
- **Spec:** `specs/fulfillment-order/shipping-agent/`
- **Next:** Add richer location-event enrichment behind `enablements-api`

#### Deployment (k8s / Worker Versioning rollout)
- **Status:** Not Started — follow-up spec
- **Goal:** K8s deployment changes for `fulfillment-workers`, Worker Versioning rollout for `apps` and `processing` task queues
- **Next:** Spec to be written after fulfillment-order-workflow spec is approved

### OMS Evolution
- **Status:** Draft - Ready for Review
- **Goal:** Represent every `apps.Order`/`processing.Order`/fulfillment version as real, coexisting code (package-per-version), and define OMS version as a named pin of one apps version + one processing version + one fulfillment version, so workshops/demos can run any point in the OMS's evolution
- **Owner:** Temporal FDE Team
- **Spec:** `SPECS/oms-evolution/spec.md`
- **Sibling doc:** `SPECS/oms-evolution/hosting.md` - hosting/running/accessing OMS versions, reviewed separately: Mode A runs every version at once with per-request pinning, Mode B does on-demand sequential rollouts (reusing/fixing `WorkerVersionEnablementImpl`/`DeploymentActivitiesImpl`) with load and deployment kept independent
- **Next:** Awaiting tech lead review (see PROGRESS.md)

### Worker Version Enablement
- **Status:** Draft - Ready for Review
- **Goal:** Generate load + deploy worker versions + validate zero failures
- **Owner:** [Your Name]
- **Next:** Awaiting tech lead review (see PROGRESS.md)
- **Note:** Its `worker-versioning` sub-spec (`SPECS/enablements/worker-versioning/`) is superseded by `SPECS/oms-evolution/hosting.md`'s Mode B; `load-generation` and `validation-framework` are unaffected

## Example: Complete Workflow

```
SPECS/my-feature/
├── spec.md
│   └── [Author writes comprehensive design]
│
├── PROGRESS.md
│   ├── Open items: "What should V2 change?" "How long test?"
│   └── Ready for review
│
→ [Tech Lead reviews spec.md]
│   ├── Provides feedback in PROGRESS.md
│   └── Marks as APPROVED WITH CHANGES or APPROVED
│
→ [Author revises if needed]
│
→ [Planning Phase begins]
│   ├── Break into smaller tasks
│   ├── Create Jira/Linear tickets
│   ├── Assign owners + dates
│   └── Update PROGRESS.md with roadmap
│
→ [Implementation begins]
│   └── Reference spec for design decisions
│   └── Update PROGRESS.md with phase status
│
→ [Validation & Demo]
│   ├── Run acceptance tests
│   ├── Demo to team
│   └── Update PROGRESS.md: COMPLETE
│
→ [Post-Ship]
    ├── Capture lessons learned
    ├── Update runbooks
    └── Archive spec
```

## Tips

- **Be specific:** "Create REST service on port 8082" beats "Add new service"
- **Show your work:** Include architecture diagrams, data models, example configs
- **Question everything:** Explicitly list assumptions and open questions
- **Think about ops:** How will this be deployed? Monitored? Debugged?
- **Assume async:** Tech lead may not respond for days; spec should be self-contained
- **One feature per spec:** Tempting to bundle related work; resist (easier to scope, review, iterate)

## Questions?

Specs are a tool to improve collaboration. If something's unclear:
- Add it to the template
- Create an example in this README
- Ask in team sync

---

**Last Updated:** 2026-03-18
**Owner:** [Engineering Team]
