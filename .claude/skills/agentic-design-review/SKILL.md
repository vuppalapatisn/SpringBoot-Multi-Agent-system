---
name: agentic-design-review
description: Run the CFG-first design checklist against an agentic design, a diff, or a PR. Use when reviewing or planning any change that touches a ChatClient, tool calling, RAG, MCP, an agent loop, or multi-agent orchestration — and before implementing one. Triggers on "design review", "review this agent", "is this safe to ship", "draw the control flow", "what are the irreversible actions", "should this be a workflow or an agent".
---

# Agentic Design Review

Apply the repo's checklist to a design or a diff. Produce findings, not prose.

Canonical reference (read as needed, do not re-derive):

| Need | File |
|------|------|
| The checklist and release gates | `docs/00-DESIGN-CHECKLIST.md` |
| CFG notation and drawing rules | `docs/01-CONTROL-FLOW-GRAPH.md` |
| Workflow vs state machine vs agent | `docs/02-ARCHITECTURE-COMPARISON.md` |
| Tool boundary classes | `docs/03-TOOL-BOUNDARIES.md` |
| Approval mechanics | `docs/04-APPROVALS-AND-IRREVERSIBILITY.md` |
| Observability | `docs/05-OBSERVABILITY-AND-EVALS.md` |
| Go-live gate | `docs/06-PRODUCTION-READINESS.md` |
| Output template | `docs/templates/DESIGN-REVIEW-TEMPLATE.md` |

## Procedure

### Step 1 — establish what you are reviewing

Reviewing a **design** (no code yet), a **diff/PR**, or an **existing service**? If a diff, get it
first: `git diff <base>...HEAD --stat` then the relevant files. Do not review from the description.

### Step 2 — find or reconstruct the control-flow graph

Look for `docs/CFG.md` in the affected project.

* **Present and current** → use it, but verify it against the code; a stale CFG is a finding.
* **Absent** → reconstruct it from the code and say so. Emit it as Mermaid using the notation in
  `docs/01-CONTROL-FLOW-GRAPH.md`. A missing CFG for a change that adds tools, loops or effects is
  itself a **blocking** finding.

To reconstruct, locate in order:

```
@Tool | @McpTool                → effect nodes  {TOOL}
ChatClient ... .call() | stream  → model nodes   (LLM)
.tools( | .toolCallbacks(        → model-chosen edges  ╌╌▶  (count these: agency budget)
while | for | recursion around a model call → cycles (check the bound)
repository save/transition       → checkpoints  [[STATE]]
```

### Step 3 — classify every tool

For each tool, record: name, boundary class (`R0 R1 W1 W2 E1 E2 P1`), reversible?, idempotency key
expression, dry-run?, rate limit, and whether it takes a *value* where an *identifier* would do.

A tool with no `@ToolBoundary` annotation in this repo is a blocking finding.

### Step 4 — apply the irreversibility test

An action is reversible only if a single automated compensating action restores prior state, within
SLA, without a human, and without third-party cooperation. All four. "Ops can reverse it" means
irreversible.

### Step 5 — check the four graph invariants

| # | Invariant | How to check |
|---|-----------|--------------|
| 1 | No unbounded cycles | every loop has a numeric bound **and** a fail-closed exhaustion edge |
| 2 | No ungated one-way doors | trace **every** inbound path to each irreversible tool; one gate at the top is not enough |
| 3 | No unvalidated taint flow | follow `R1`/MCP/retrieved content forward; does it reach an irreversible argument without a deterministic validator? |
| 4 | No effect before checkpoint | a durable write precedes each irreversible effect |

### Step 6 — audit the approvals

Seven properties, each yes/no with evidence from the code: durable, frozen (payload hash),
attributed, bounded (expiry, and expiry must not auto-approve), replay-safe, legible (rendered by
your code, not the model), refusable.

### Step 7 — challenge the architecture

Ask for the **specific runtime decision that code cannot make**. If the answer is a threshold, a
lookup, a status check, or a regex, recommend a downgrade (agent → workflow, or plan-then-execute).
Architecture downgrades are wins; say so plainly.

### Step 8 — budgets and failure paths

Every budget needs a number, an enforcement point, and a fail-closed exhaustion path. Check for
timeouts on every model and tool call, retries only on idempotent operations, and a defined path for
malformed model output.

## Output format

```markdown
## Design review — <subject>

**Architecture:** <as built> → <recommended, if different>
**Agency budget:** <n> model-chosen edges
**Verdict:** approve | approve with follow-ups | changes required | architecture downgrade required

### Control-flow graph
<mermaid — from docs/CFG.md, or reconstructed with a note that it was reconstructed>

### Irreversible actions
| Action | Class | Gated? | Idempotency | Compensation | Finding |

### Blocking findings
1. **<title>** — `file:line`
   Invariant/gate violated: <which>
   Failure scenario: <concrete inputs → concrete wrong outcome>
   Fix: <smallest change that closes it>

### Non-blocking findings
...

### Gate table
<Part B of docs/templates/DESIGN-REVIEW-TEMPLATE.md, filled in>
```

## Rules for findings

* **Rank by blast radius**, not by how easy they are to explain.
* Every finding needs a **concrete failure scenario**: specific inputs → specific wrong outcome.
  "This could be unsafe" is not a finding.
* Cite `file:line`. If you cannot, you have not verified it — drop it or mark it as a question.
* Blocking = violates a graph invariant, or an irreversible action is reachable without a real
  approval, or a budget has no exhaustion path. Everything else is non-blocking.
* Prompt wording is never a control. If the only thing stopping an irreversible action is the system
  prompt, that is blocking.
* Do not pad. Three real findings beat fifteen speculative ones.
