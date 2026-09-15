# Approvals & Irreversibility

Two related questions, in the right order:

1. **What cannot be undone?** (irreversibility — a property of the world)
2. **Who says yes before it happens?** (approval — a property of your system)

Teams usually start at 2 and guess at 1. Do it the other way round.

---

## 1. The irreversibility test

An action is **reversible** only if all four hold:

| # | Condition | Common failure |
|---|-----------|----------------|
| 1 | A single automated compensating action restores the prior state | "we'd run three scripts" |
| 2 | Within the business SLA | "the reversal clears in 5 working days" |
| 3 | Without a human | "ops raises a ticket" |
| 4 | Without cooperation from a third party you do not control | "we ask the payment provider" |

Fail any one → **irreversible**. Be strict here; the cost of over-classifying is one approval click,
and the cost of under-classifying is an incident.

### Partially reversible: the compensation window

Many effects are reversible *for a while*. That window is a design input, not trivia.

| Action | Window | After the window |
|--------|--------|------------------|
| Refund (pre-settlement) | ~30 min | irreversible |
| Email | ~0 s | irreversible |
| Public post | until someone sees it | socially irreversible |
| Record delete (soft) | retention period | irreversible |
| Provisioned resource | until billed / referenced | expensive |

Encode the window as a **timer**, not a comment. In project 07 it is
`RefundSaga.compensationDeadline`, and a scheduled sweep marks the run `IRREVERSIBLE` when it passes
so that no later code path assumes it can still undo.

---

## 2. Choosing the gate

Pick the weakest sufficient gate, placed **before** the one-way door.

| Gate | Human cost | Use when |
|------|-----------|----------|
| **Policy gate** (no human) | none | a deterministic rule fully decides: amount < threshold, allowlisted payee, risk = LOW |
| **Pre-commit approval** | 1 per action | default for `E2` / `W2` / `P1` |
| **Plan approval** (batch) | 1 per run | several related actions; approve the plan, then execute it unchanged |
| **Dual control** | 2 per action | irreversible **and** blast radius beyond one record (money above a limit, bulk operations, privilege changes) |
| **Post-hoc review** | sampling | reversible actions only |

Most production systems need a **tiered** policy, which is what this repo implements:

```
amount ≤ $100  AND risk = LOW  AND age ≤ 30d   ──▶ auto-approve (policy gate)
amount ≤ $2,000                                 ──▶ single approver: refund-lead
amount  > $2,000  OR risk = HIGH                ──▶ dual control: refund-lead + finance
any mismatch between model proposal and order    ──▶ decline, do not escalate
```

Note the last line. A model proposing an amount that does not match the order is not an approval
question, it is a bug or an attack. Escalating it trains approvers to rubber-stamp.

---

## 3. The seven properties of a real approval

An approval that lacks any one of these is theatre.

### 3.1 Durable

The run **suspends to storage**. A pod restart must not lose the pending approval, and must not
resume it as approved. Concretely: state `AWAITING_APPROVAL` is a persisted row, not a thread parked
on a `CompletableFuture`.

```java
// project 07 — RunRepository
run.transitionTo(AWAITING_APPROVAL, payloadHash, expiresAt);   // committed before returning
```

### 3.2 Frozen

The approved payload is the **exact arguments**, hashed. The model gets no further turn between
approval and execution.

```java
String hash = PayloadHash.of(proposal);           // canonical JSON → sha256
// ... hours later ...
if (!hash.equals(request.payloadHash())) throw new ApprovalMismatchException();
```

This is the control that defeats the most dangerous failure in agentic systems: the approver sees
"refund $24.00", the model is invoked again on resume, and it now proposes $2,400. Freeze the
payload and that path does not exist.

### 3.3 Attributed

Approver identity, timestamp, payload hash, and the decision are written to an **append-only** log.
Not the application log — a table nobody has `UPDATE` on.

### 3.4 Bounded

An expiry, with a defined default: **expire, never auto-approve**.

| Timeout policy | Verdict |
|----------------|---------|
| expire → terminal `EXPIRED` | correct |
| escalate to a second approver, then expire | correct for high urgency |
| auto-approve after N hours | **never** for irreversible actions |
| wait forever | operationally equivalent to a leak |

### 3.5 Replay-safe

Resume twice → execute once. The idempotency key is checked at the effect boundary, not at the API
boundary, because a duplicate resume can arrive from a retrying UI, a timer, and a human all at once.

### 3.6 Legible

The approver sees the real effect, rendered by **your** code from the frozen payload:

> Refund **$240.00** to card ••4242
> Order A-1187 · customer c-5512 · placed 2026-08-02
> Reason: item not received · Risk: LOW · Policy: within 30-day window
> Model proposed this decision; amount verified against order total.

Not: a JSON blob, and not the model's own summary of what it is about to do. If the model writes the
approval text, the model can lie in the approval text.

### 3.7 Refusable

A rejection path exists, leads to a terminal state, and is tested. If rejection just loops back to
the model to "try again", the approver cannot actually say no.

---

## 4. Two-phase execution

For irreversible effects, split intent from effect so a crash is always diagnosable:

```
Phase 1 — RECORD INTENT (durable, reversible)
    write: run id, tool, frozen args, idempotency key, status=PENDING
    ▼
Phase 2 — EXECUTE (irreversible)
    call provider with the idempotency key
    ▼
Phase 3 — RECORD OUTCOME
    status=APPLIED + provider receipt   |   status=FAILED + error
```

Crash between 1 and 2: a sweeper finds `PENDING` rows and reconciles with the provider *by
idempotency key* — it can ask "did this happen?" instead of guessing. Without Phase 1 there is no
key to ask about, and reconciliation becomes a manual investigation.

Crash between 2 and 3: the same reconciliation finds the effect applied and completes the record.

This is why [Gate 1 invariant 4](00-DESIGN-CHECKLIST.md#phase-1--draw-the-control-flow-graph-before-any-code)
requires a `[[STATE]]` before every `⚠`.

---

## 5. Compensation and sagas

When a run performs several effects, define the compensation for each and the order of unwinding.

| Step | Effect | Compensation | Window |
|------|--------|--------------|--------|
| 1 | create refund draft (`W1`) | delete draft | always |
| 2 | issue refund (`E2` ⚠) | `cancelRefund` | ≤ 30 min, pre-settlement |
| 3 | notify customer (`E2` ⚠) | **NONE** — send a correction, which is a new effect | — |

Rules:

* Compensate in **reverse order**, and only steps that are still inside their window.
* A compensation that fails is an **incident**, not a retry loop — it needs a human and an alert.
* **Order effects by reversibility**: do everything reversible first, and put the least reversible
  effect last. Step 3 is the email precisely because it cannot be undone; if step 2 fails, nothing
  was said to the customer.
* Compensation is itself an effect: audited, idempotent, rate-limited.

---

## 6. Anti-patterns

| Anti-pattern | What actually happens |
|--------------|-----------------------|
| "The system prompt says to ask first" | The model asks 99 times and acts on the 100th. |
| Approval as a chat turn | Lost on restart; nothing frozen; no attribution. |
| Model writes the approval summary | The approver approves a description, not the action. |
| Model generates the idempotency key | Key changes on retry → duplicate payment. |
| Re-prompting the model after approval | Executes something the approver never saw. |
| Auto-approve on timeout | The unavailability of a human becomes a yes. |
| One gate at the top of the graph | Any later path to the tool is ungated. |
| Gate implemented as an advisor | Advisors wrap the model call, not the effect; direct calls bypass it. |
| Retrying the whole run | Replays effects that already succeeded. |
| Approving `N` actions with one click, then letting the model change `N` | Batch approval must freeze the whole plan. |

---

## 7. Implementation map

| Concept | Where |
|---------|-------|
| `@ToolBoundary` metadata | [02](../02-tool-calling-guardrails/) |
| `GuardedToolExecutor` — refuses irreversible calls without a token | [02](../02-tool-calling-guardrails/) |
| Tiered `PolicyGate` | [02](../02-tool-calling-guardrails/), [06](../06-workflow-orchestration/) |
| In-memory approval store + payload freeze | [02](../02-tool-calling-guardrails/) |
| **Durable** approval as a state, with expiry timer | [07](../07-state-machine-orchestration/) |
| Two-phase execution + reconciliation sweeper | [07](../07-state-machine-orchestration/) |
| Saga compensation with windows | [07](../07-state-machine-orchestration/) |
| Runtime gate inside an agent loop | [08](../08-autonomous-agent-loop/) |
| Escalation rights per agent | [09](../09-multi-agent-supervisor/) |
| Server-side approval-required result over MCP | [04](../04-mcp-server-tools/) |

---

## 8. Tests you must have

```
✓ irreversible tool called without a token            → refused
✓ approval with a mismatched payload hash             → refused
✓ approval expired                                    → terminal EXPIRED, no effect
✓ approval store unavailable                          → refused (fail closed)
✓ resume twice with the same token                    → effect applied once
✓ rejection                                           → terminal DECLINED, no effect
✓ dual control with one approver                      → refused
✓ execution-mode = DRY_RUN                            → no writes, synthetic receipt
✓ crash between intent and effect (simulated)         → sweeper reconciles by idempotency key
✓ compensation after the window closed                → refused, alert raised
```

Every one of these exists in this repo. See
[`07-state-machine-orchestration/src/test/java/.../ApprovalGateTest.java`](../07-state-machine-orchestration/)
and [`02-tool-calling-guardrails/src/test/java/.../GuardedToolExecutorTest.java`](../02-tool-calling-guardrails/).

---

**Next:** [05 — Observability & Evaluation](05-OBSERVABILITY-AND-EVALS.md)
