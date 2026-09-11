# AGENTS.md

This repository contains the ACME Order Management System reference app, a
multi-namespace Temporal workshop application with Java workers, Python
fulfillment and agent workflows, generated protobuf contracts, Kubernetes
deployment assets, and a Svelte web UI.

This file is the portable, agent-neutral source of instructions for working
in this repository, read by Codex, Claude Code, and any other AGENTS.md-aware
coding agent. It does not assume any particular agent's tooling, skills, or
plugin ecosystem.

## Agent Behavior Rules

Claude Code additionally reads cross-cutting behavior rules from
`.claude/rules/` (indexed at `.claude/rules/README.md`): shell and file-editing
conventions, output style, and authoring hygiene. These are Claude
Code-specific mechanics layered on top of this file, not a replacement for it.
Agents that don't read `.claude/` should rely on this file alone; the
repository behaves identically either way.

## Temporal Domain Knowledge

This is a Temporal repository: workflows, activities, workers, Nexus
operations, namespaces, and Worker Versioning are core to the design. If your
agent has Temporal-specific tooling available (a skill, plugin, or MCP
server), use it. Otherwise, work from this repository's own docs, specs,
code, and tests, and consult the official Temporal documentation
(https://docs.temporal.io) for SDK and product behavior you're unsure of.

## Repository Orientation

Before making changes:

1. Inspect `git status` so existing user changes are visible.
2. Read `README.md` for the app model and current workshop shape.
3. Read `specs/README.md` and any relevant feature spec under `specs/`.
4. Read the relevant local guide: `docs/GETTING_STARTED.md`,
   `docs/DEVELOPMENT.md`, `docs/DEPLOYMENT.md`, or the matching workshop docs.
5. Inspect nearby tests and generated-code boundaries before editing code.

## Temporal Engineering Constraints

- Preserve workflow determinism. Do not add wall-clock time, random values,
  network calls, filesystem access, environment reads, mutable globals, or
  other non-deterministic behavior inside workflow code.
- Put I/O, service calls, LLM calls, filesystem access, randomness, and
  secret-bearing operations in Activities or other appropriate Temporal
  primitives, not directly in Workflows.
- Treat workflow history as durable data. Do not put secrets, API keys, access
  tokens, or unnecessary PII in workflow inputs, signals, updates, search
  attributes, logs, or exceptions.
- Maintain bounded-context separation. The app uses `apps`, `processing`, and
  fulfillment boundaries; cross-boundary calls should preserve the existing
  Temporal namespace and Nexus model unless a spec changes it.
- Worker Versioning and Temporal Deployments are part of the product surface.
  When changing worker behavior, consider replay compatibility, pinned
  workflows, drain behavior, build IDs, and rollout scripts.
- Add or update replay tests for workflow behavior changes. A single workflow
  type still needs replay coverage when its logic changes.
- For protobuf changes, update the source `.proto` files first, then regenerate
  Java, Python, and web outputs with the repo's buf workflow.
- Keep Temporal Cloud and local Temporal behavior distinct in docs and scripts.
  Do not assume one environment when the current context is ambiguous.

## Validation

Run the narrowest checks that cover the change, and broaden when the change
crosses module or runtime boundaries.

Common checks:

```bash
mvn -f java/pom.xml test
uv run --project python pytest
npm --prefix web run check
npm --prefix web run lint
buf lint proto
```

For workshop or deployment changes, also validate the relevant script path under
`scripts/`, `k8s/`, or `workshop/`, and report any environment prerequisites
that prevented a full run.
