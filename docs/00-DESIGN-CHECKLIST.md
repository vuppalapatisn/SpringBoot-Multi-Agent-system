# The CFG-First Agentic Design Checklist

**Use this before writing agent code. Every phase produces an artefact. No artefact, no merge.**

The checklist exists because agentic systems fail differently from ordinary services. An ordinary
service does what the code says. An agentic service does what a probability distribution decided,
using credentials you gave it, against systems that may not have an undo button. The controls
therefore have to sit at the *graph* level, not the prompt level.

Copy [`templates/DESIGN-REVIEW-TEMPLATE.md`](templates/DESIGN-REVIEW-TEMPLATE.md) into your feature
branch and fill it in as you work through the phases below.

---

## How to use it

| Scope | Phases required |
|-------|-----------------|
| Read-only assistant (no tools, no egress) | 0, 1, 5, 9, 10 |
| RAG / retrieval over internal content | 0, 1, 2, 5, 9, 10 |
| Anything with a tool that writes | **all** |
| Anything that touches money, identity, public content, or customers | **all**, plus dual sign-off in Phase 10 |
| Multi-agent | **all**, plus Phase 6 handoff contracts |

Timebox: a first pass on a moderate feature takes 60–90 minutes with two people and a whiteboard.
That is cheaper than one production incident involving a duplicate payment.

---

## Phase 0 — Frame the job

Answer these in one page. If you cannot, the design is not ready and no amount of prompt
engineering will fix that.

- [ ] **Job statement** — one sentence: *"Given X, the system decides Y and may do Z."*
- [ ] **Actors** — who or what triggers a run (user, webhook, schedule, another agent).
- [ ] **Authority** — whose permissions does the run use? A service account, or the user's? (If a
      service account, you have just built a confused-deputy risk; note it.)
- [ ] **Definition of success** — measurable, per run.
- [ ] **Definition of failure** — and which failures are *acceptable* vs. *never acceptable*.
- [ ] **Data classes touched** — public / internal / confidential / regulated (PII, PCI, PHI).
- [ ] **Non-goals** — what this system will explicitly refuse to do.
- [ ] **SLO** — p95 latency, throughput, and a cost ceiling **per run** (not per month).

> **Gate 0:** a run that exceeds its per-run cost ceiling must be terminable. If you have no ceiling,
> you have no ceiling.

---

## Phase 1 — Draw the control-flow graph (before any code)

This is the core of the method. Full notation and worked examples:
[01 — Control-Flow Graph Method](01-CONTROL-FLOW-GRAPH.md).

Node types:

| Node | Meaning |
|------|---------|
| `IN` | ingress — where untrusted input enters |
| `LLM` | a model decision (nondeterministic branch point) |
| `TOOL` | an effect on the world, classified in Phase 2 |
| `GATE` | a deterministic policy or approval decision |
| `STATE` | a durable checkpoint you could resume from |
| `OUT` | egress — data leaving your boundary |
| `TERM` | terminal state (success, declined, failed, expired) |

Edge types: `——>` deterministic, `- ->` **model-chosen**, `↺` retry/loop, `⇠⇠` compensation.

- [ ] Graph drawn with every node labelled and every edge typed.
- [ ] **Agency budget** recorded: count the `- ->` edges. That integer *is* your nondeterminism.
      Justify each one — if a branch can be decided by code, it must be.
- [ ] Every cycle has an explicit bound written on it (max iterations / tokens / wall clock).
- [ ] Every `TOOL` node has a boundary class from Phase 2.
- [ ] Every irreversible `TOOL` is immediately preceded by a `GATE` **and** a `STATE`.
- [ ] Every `TERM` is reachable, and every path reaches some `TERM`.
- [ ] Trust boundaries drawn as a dashed box around the untrusted region (see Phase 5).

> **Gate 1 — the four graph invariants. A design fails review if any is violated:**
> 1. **No unbounded cycles.** Every loop has a numeric bound and a fail-closed exhaustion path.
> 2. **No ungated one-way doors.** Every irreversible `TOOL` has a `GATE` on every inbound path.
> 3. **No unvalidated taint flow.** No path lets external content (`R1`) reach an irreversible
>    tool's arguments without a deterministic validator in between.
> 4. **No effect before checkpoint.** Every irreversible `TOOL` is preceded by a `STATE` that
>    records intent, so a crash mid-flight is diagnosable and replay-safe.

---

## Phase 2 — Classify every tool boundary

Full table with required controls: [03 — Tool Boundaries](03-TOOL-BOUNDARIES.md).

| Class | Name | Example | Reversible? |
|-------|------|---------|-------------|
| `R0` | read, internal | own DB / cache read | n/a |
| `R1` | read, external | web fetch, partner API, another agent's output | n/a — **but it is tainted input** |
| `W1` | write, internal, reversible | create draft, set status | yes, automatically |
| `W2` | write, internal, irreversible | hard delete, ledger post, schema change | **no** |
| `E1` | egress, external, reversible | update a draft PR, edit a record in a partner system | yes, with cooperation |
| `E2` | egress, external, irreversible | payment, email/SMS, publish, provision | **no** |
| `P1` | privilege change | IAM, secrets, config, feature flags | technically yes, blast radius no |

- [ ] Every tool in the codebase carries its class **in code**, not only in a doc
      (see `RefundTools` / `ToolBoundary` in project 02).
- [ ] No tool spans two classes. A tool that reads *and* pays is two tools.
- [ ] Tool descriptions are written for the model but reviewed as **security surface**: they are
      instructions an attacker would love to influence.
- [ ] Tool arguments are typed and validated (Bean Validation), never free-form strings that get
      interpolated into a query, path, URL, or shell.
- [ ] Every `W*`/`E*` tool accepts an **idempotency key** derived from the run, not from the model.
- [ ] Every `W*`/`E*` tool has a **dry-run** mode used by tests and by the plan-preview endpoint.

> **Gate 2:** an `E2` or `W2` tool whose signature does not include an idempotency key does not ship.

---

## Phase 3 — Name the irreversible actions

**The test.** An action is irreversible unless *all* of these hold:

1. a single automated compensating action restores the prior state,
2. within the business SLA,
3. without a human, and
4. without cooperation from a third party you do not control.

"We can raise a ticket to reverse it" means **irreversible**.

For each irreversible action, record a row in the project's `docs/CFG.md`:

| Field | Why |
|-------|-----|
| Action | tool name + operation |
| Blast radius | one record / one customer / all customers / money amount |
| Detection latency | how long before anyone notices it was wrong |
| Compensation | exact procedure, or `NONE` |
| Compensation window | e.g. "refund cancellable until settlement, ~30 min" |
| Approval authority | role that may approve, and whether dual control applies |
| Idempotency key | the exact expression |
| Dry-run | available? |
| Rate limit | per run / per customer / per hour |
| Audit record | what is written, where, immutably |

- [ ] Catalogue complete, reviewed by someone who owns the downstream system.
- [ ] Actions with `Compensation: NONE` **and** blast radius > one record require dual control.
- [ ] Anything with a compensation window has that window encoded as a timer, not a comment.

> **Gate 3:** if the catalogue is empty, prove it. A system with tools and no irreversible actions
> is rare and should be stated explicitly, not assumed.

---

## Phase 4 — Place the approval points

Pick the weakest gate that is still sufficient, and put it **before** the one-way door.

| Pattern | When | Cost |
|---------|------|------|
| **Pre-commit gate** | default for `E2`/`W2`/`P1` | one interruption per action |
| **Plan approval (batch)** | many related actions, one decision | one interruption per run |
| **Policy gate (no human)** | deterministic rule fully decides it (amount < threshold, allowlisted payee) | none |
| **Post-hoc review** | reversible actions only | none, but needs real review |
| **Dual control** | irreversible + wide blast radius | two interruptions |

An approval is only real if all seven hold:

- [ ] **Durable** — the run suspends to storage; a pod restart does not lose or auto-approve it.
- [ ] **Frozen** — the approved payload is the *exact* arguments, hashed. The model does not get
      another turn between approval and execution.
- [ ] **Attributed** — approver identity, timestamp, and the hash are persisted immutably.
- [ ] **Bounded** — an expiry with a defined default (**expire, never auto-approve**).
- [ ] **Replay-safe** — resuming twice executes once (idempotency key checked at the boundary).
- [ ] **Legible** — the approver sees the real effect ("Refund $240.00 to card ••4242, order
      A-1187"), not a JSON blob or a paraphrase from the model.
- [ ] **Refusable** — a rejection path exists, is tested, and leads to a `TERM`.

> **Gate 4:** the approval must fail **closed**. Storage down, timer expired, approver unknown,
> hash mismatch → do not execute. Test each of those four cases.

---

## Phase 5 — Draw the trust boundaries

- [ ] **Prompt boundary** — mark every place untrusted content enters the context window: user
      input, retrieved documents, web pages, tool results, other agents' messages, MCP tool
      descriptions.
- [ ] Untrusted content is **labelled as data** in the prompt and never concatenated into the
      system message.
- [ ] **Tool boundary** — model output becomes tool arguments here. Validate, don't trust.
- [ ] **MCP boundary** — one trust level *per server*. Record: who operates it, tool allowlist,
      behaviour on tool-list change ("rug pull"), name-collision policy, and whether its tool
      descriptions are treated as untrusted (they are).
- [ ] **Egress boundary** — every `OUT` node: recipient allowlist, PII redaction, data
      classification check.
- [ ] **The taint rule** is enforced in code: content that crossed an `R1`/MCP boundary may not
      select a tool or populate an `E2`/`W2` argument without a deterministic validator.

> **Gate 5:** for each trust boundary, name the validator function and its test.

---

## Phase 6 — Choose the architecture deliberately

Full matrix: [02 — Architecture Comparison](02-ARCHITECTURE-COMPARISON.md). Short version:

| If… | Build |
|-----|-------|
| Steps and order are known at design time | **Workflow** (project 06) |
| Long-running, needs durable pause/resume, audit per transition, compensation | **State machine** (project 07) |
| The set of steps genuinely depends on findings at runtime | **Agent loop** (project 08) |
| Work splits into specialities with different tools/authority | **Multi-agent** (project 09) |
| You are unsure | **Workflow.** Escalate only when you can point at the decision code cannot make. |

- [ ] Choice recorded with the *specific* reason, not "agents are flexible".
- [ ] The nondeterminism is confined to the smallest possible node — prefer a model that *classifies*
      inside a deterministic workflow over a model that *drives* the workflow.
- [ ] If multi-agent: each agent has a written **handoff contract** (typed input, typed output,
      allowed tools, budget, and who may escalate to a human).
- [ ] Least authority: no agent holds a credential it does not need for its own tools.

> **Gate 6:** "agent" chosen without a named runtime decision that code cannot make is a review
> failure. Downgrade it.

---

## Phase 7 — Bound the loop

Every one of these needs a number and an enforcement point:

- [ ] max steps / iterations
- [ ] max tool calls, total **and** per tool (`spring.ai.tools.limits.*`)
- [ ] max tokens per run (prompt + completion), and max context growth
- [ ] max cost per run (currency)
- [ ] wall-clock deadline (and it must actually cancel in-flight work)
- [ ] max retries per tool, only for idempotent tools
- [ ] max consecutive no-progress steps (loop detection: same tool, same args)
- [ ] concurrency cap per customer / per tenant

- [ ] **Exhaustion behaviour** defined per budget, and it **fails closed**: escalate to a human or
      terminate. Never "do the risky thing because we ran out of budget for checking".

> **Gate 7:** there is a test that drives each budget to exhaustion and asserts the terminal state.

---

## Phase 8 — Design the failure paths

- [ ] Timeout on every model call and every tool call.
- [ ] Retries only where the tool is idempotent; jittered backoff; retry budget.
- [ ] Circuit breaker per downstream dependency.
- [ ] **Partial-failure semantics** written down: if step 3 of 5 fails after an `E2` succeeded, what
      is the state of the world, and who fixes it?
- [ ] Compensation / saga path for every multi-step effect, with the window from Phase 3.
- [ ] Malformed or unparseable model output → a defined path, not an exception into a 500.
- [ ] Model refusal or safety stop → a defined path.
- [ ] Poison-input handling: a run that always fails must stop retrying and be visible.
- [ ] Degraded mode: what still works when the model provider is down?

> **Gate 8:** kill the model provider in a test and assert the system degrades instead of hanging.

---

## Phase 9 — Observability, evaluation, replay

Details: [05 — Observability & Evaluation](05-OBSERVABILITY-AND-EVALS.md).

- [ ] **Run ID** on every log line, span, and record; **step ID** within a run.
- [ ] Every model decision recorded: prompt hash, model + version, options, usage, latency, finish
      reason.
- [ ] Every tool call recorded: name, class, arguments (redacted), result, duration, outcome.
- [ ] Every gate recorded: decision, rule or approver, and the payload hash.
- [ ] Budget consumption emitted as metrics (tokens, cost, steps) with alerts near the ceiling.
- [ ] **Replay**: a run can be reconstructed from its records for a post-incident review.
- [ ] Offline eval set with at least: happy path, boundary cases, adversarial prompts, and one case
      per irreversible action asserting the gate fired.
- [ ] Online guardrail metrics: approval rate, rejection rate, escalation rate, budget-exhaustion
      rate, tool-error rate. Alert on *change*, not just absolute value.
- [ ] Prompts, tool descriptions, and policy thresholds are versioned artefacts.

> **Gate 9:** you can answer "why did run `r-8831` pay $240?" from stored data alone, without
> re-running the model.

---

## Phase 10 — Release gate

Sign-off table — copy into the PR description.

| Gate | Statement | Owner | Status |
|------|-----------|-------|--------|
| 0 | Job, authority, data classes and per-run cost ceiling are documented | Product | ☐ |
| 1 | CFG committed; four graph invariants hold | Eng | ☐ |
| 2 | Every tool classified in code; idempotency + dry-run on all `W*`/`E*` | Eng | ☐ |
| 3 | Irreversible-action catalogue complete and reviewed by downstream owner | Eng + Ops | ☐ |
| 4 | Approvals durable, frozen, attributed, bounded, replay-safe, legible, refusable | Eng | ☐ |
| 5 | Trust boundaries drawn; taint rule enforced by a named validator | Security | ☐ |
| 6 | Architecture choice justified by a named runtime decision | Eng | ☐ |
| 7 | All budgets numeric, enforced, and exhaustion-tested | Eng | ☐ |
| 8 | Failure paths, compensation and degraded mode implemented and tested | Eng | ☐ |
| 9 | Run replay, evals and guardrail metrics in place | Eng | ☐ |
| 10 | Runbook: kill switch, how to revoke tool access, who to call | Ops | ☐ |

**Kill switch requirement.** There is a single configuration change that disables all `E2`/`W2`
tools without a deploy, and it is tested. In this repo: `agentic.tools.execution-mode=DRY_RUN`.

---

## Quick review — the ten questions

If you have five minutes and someone else's design, ask these:

1. Show me the graph. (No graph → stop here.)
2. Which actions cannot be undone?
3. What stops each of those from happening twice?
4. Who approves, and what exactly do they see?
5. What happens if the approver never responds?
6. Where does untrusted text enter the context?
7. Can that text choose a tool or fill an argument?
8. What bounds the loop, and what happens at the bound?
9. Could this be a workflow instead of an agent?
10. After an incident, how do you find out what it did?

Anti-patterns that should stop a review immediately:

| Anti-pattern | Why it fails |
|--------------|--------------|
| "The prompt tells it to ask before paying" | A prompt is not an access control. |
| Approval as a chat turn | Not durable, not frozen, not attributed. |
| Model generates the idempotency key | Not stable across retries; it will pay twice. |
| One agent, all credentials | No least authority, no blast-radius containment. |
| Retry the whole run on failure | Replays effects that already happened. |
| `maxIterations` with no exhaustion path | Loops silently become timeouts, then 500s. |
| Retrieved document text merged into the system prompt | Direct prompt injection into your own instructions. |
| Tool named `executeQuery(sql)` | You shipped an arbitrary-code-execution tool. |

---

**Next:** [01 — Control-Flow Graph Method](01-CONTROL-FLOW-GRAPH.md) ·
[02 — Architecture Comparison](02-ARCHITECTURE-COMPARISON.md)
