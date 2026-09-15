# Agentic Design Review — `<feature>`

> Paste into the PR description. Reviewer works top to bottom and stops at the first ✗.
> Full reference: [00 — Design Checklist](../00-DESIGN-CHECKLIST.md).

| | |
|---|---|
| Feature | |
| Author | |
| Reviewer(s) | |
| CFG | link to `docs/CFG.md` |
| Architecture | workflow \| state machine \| agent \| multi-agent \| hybrid |
| Scope tier | read-only \| retrieval \| writes \| **money/identity/public/customer** |

---

## Part A — the ten questions (5 minutes)

| # | Question | Answer | ✓/✗ |
|---|----------|--------|-----|
| 1 | Where is the control-flow graph? | | |
| 2 | Which actions cannot be undone? | | |
| 3 | What stops each of those happening twice? | | |
| 4 | Who approves, and what exactly do they see? | | |
| 5 | What happens if the approver never responds? | | |
| 6 | Where does untrusted text enter the context? | | |
| 7 | Can that text choose a tool or fill an argument? | | |
| 8 | What bounds the loop, and what happens at the bound? | | |
| 9 | Could this be a workflow instead of an agent? | | |
| 10 | After an incident, how do you find out what it did? | | |

**No graph → stop the review here.**

---

## Part B — phase sign-off

| Phase | Gate | ✓/✗ | Note |
|-------|------|-----|------|
| 0 | Job, authority, data classes, per-run cost ceiling documented | | |
| 1 | CFG committed; four graph invariants hold | | |
| 2 | Every tool classified **in code**; idempotency + dry-run on all `W*`/`E*` | | |
| 3 | Irreversible catalogue complete; reviewed by downstream owner | | |
| 4 | Approvals: durable, frozen, attributed, bounded, replay-safe, legible, refusable | | |
| 5 | Trust boundaries drawn; taint rule enforced by a named validator | | |
| 6 | Architecture justified by a named runtime decision code cannot make | | |
| 7 | All budgets numeric, enforced, exhaustion-tested | | |
| 8 | Failure paths, compensation, degraded mode implemented and tested | | |
| 9 | Replay, evals, guardrail metrics in place | | |
| 10 | Runbook + kill switch tested | | |

---

## Part C — anti-pattern scan

| Anti-pattern | Present? |
|--------------|----------|
| Control relies on prompt wording ("the prompt says to ask first") | ☐ |
| Approval is a chat turn (not durable / not frozen) | ☐ |
| Model generates the idempotency key | ☐ |
| Model re-prompted between approval and execution | ☐ |
| Auto-approve on approval timeout | ☐ |
| One gate at the top, effects deeper down | ☐ |
| Gate implemented as an advisor (bypassable) | ☐ |
| Retry wraps a segment containing an irreversible action | ☐ |
| `maxIterations` with no exhaustion path | ☐ |
| Retrieved/tool content merged into the system message | ☐ |
| Tool takes a value the model chose where an identifier would do | ☐ |
| A single agent holds all credentials | ☐ |
| LLM-as-judge gates an irreversible action | ☐ |

Any ☑ requires either a fix or a written, time-boxed exception approved by the owner named below.

---

## Part D — verdict

| | |
|---|---|
| Verdict | **approve** \| **approve with follow-ups** \| **changes required** \| **architecture downgrade required** |
| Follow-ups (with owners and dates) | |
| Exceptions granted (with expiry) | |
| Launch stage | shadow \| human-approves-all \| tiered auto-approve \| steady state |
| Sign-off | Eng: ______ Security: ______ Ops: ______ Product: ______ |
