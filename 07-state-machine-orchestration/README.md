# 07 — State Machine Orchestration

**What it teaches:** what you get when the run has somewhere to **be**. Persisted states, guarded
transitions, an audited history, approvals that expire on a timer, reconciliation after a lost
acknowledgement, and saga compensation with a real window.

> Control-flow graph: [`docs/CFG.md`](docs/CFG.md) · Agency budget: **0** ·
> Irreversible actions: **`issueRefund`, `notifyCustomer`**

Same problem as [06](../06-workflow-orchestration/), [08](../08-autonomous-agent-loop/) and
[09](../09-multi-agent-supervisor/). **Diff 06 against this one** — it is the most instructive
comparison in the repo.

---

## The one sentence

> A workflow is a function call. It has nowhere to *be* while a human takes a day to decide.

Project 06 parked pending approvals in a side map and picked them up on an unrelated second
request. Here, `AWAITING_APPROVAL` is a **row**:

```java
RefundRun run = machine.start("A-1187", "Never arrived.").orElseThrow();
assertThat(run.state()).isEqualTo(RunState.AWAITING_APPROVAL);

// a restart amounts to re-reading from storage
assertThat(runs.find(run.runId()).orElseThrow().state()).isEqualTo(RunState.AWAITING_APPROVAL);

// and advancing does nothing: the machine will not move a run a human has not decided
assertThat(machine.advance(run.runId()).state()).isEqualTo(RunState.AWAITING_APPROVAL);
```

That single difference brings expiry, per-transition audit, and crash recovery with it.

---

## Four states that earn their place

| State | Exists because |
|-------|----------------|
| `AWAITING_APPROVAL` | the run must survive a restart and be able to expire |
| `PAYOUT_PENDING` | a crash between intent and outcome must leave something a sweeper can **reconcile** |
| `COMPENSATING` | unwinding is a position, not a `catch` block |
| `NEEDS_MANUAL_INTERVENTION` | compensation that is no longer possible must **page a human**, not blend into `FAILED` |

The whole table is one method:
[`RunState.allowedNext()`](src/main/java/io/github/vuppalapatisn/agentic/statemachine/domain/RunState.java).
Read it and you know every position a run can occupy.

---

## The transition is the only way state changes

```java
// RunRepository.transition
UPDATE refund_run SET state = ?, ... WHERE run_id = ? AND state = ?
```

Two guarantees in one statement:

* **the transition table** — a move not in `allowedNext()` throws before any SQL runs;
* **optimistic concurrency** — if a sweeper and an API request race, exactly one affects a row and
  the other is told it lost.

```java
boolean moved = runs.transition(runId, RunState.CLASSIFIED, RunState.PAYOUT_PENDING, "stale-caller", ...);
assertThat(moved).isFalse();     // the run had already moved to AWAITING_APPROVAL
```

That is what stops two threads that both read `AWAITING_APPROVAL` from both paying.

Every successful transition appends an immutable row, so the audit question is answerable:

```bash
curl -s localhost:8087/api/runs/r-1a2b3c4d/transitions
```

```
1 CREATED        → CREATED          system     run created
2 CREATED        → FACTS_GATHERED   system     order, policy and fraud data gathered
3 FACTS_GATHERED → CLASSIFIED       model      classified as REFUND citing RP-30D-NOT-RECEIVED
4 CLASSIFIED     → AWAITING_APPROVAL gate      SINGLE_APPROVER_DEFAULT — awaiting 1 approval(s)
5 AWAITING_APPROVAL → PAYOUT_PENDING u-114     approved by u-114
6 PAYOUT_PENDING → PAID             system     refund applied, window open until 2026-09-16T09:30Z
7 PAID           → NOTIFIED         system     customer notified
8 NOTIFIED       → CLOSED           system     run complete
```

---

## The lost acknowledgement

This is the failure most designs skip, and the reason `PAYOUT_PENDING` exists. The provider applied
the payment; we never learned:

```java
provider.failNextPayout(true);                  // applied, but reported as failed

RefundRun stalled = machine.start("A-1204", "Broken.").orElseThrow();
assertThat(stalled.state()).isEqualTo(RunState.PAYOUT_PENDING);
assertThat(ledger.find(stalled.idempotencyKey()).orElseThrow().phase()).isEqualTo(Phase.INTENT);

sweepers.reconcileStalePayouts();               // asks the provider, BY IDEMPOTENCY KEY

assertThat(runs.find(stalled.runId()).orElseThrow().state()).isEqualTo(RunState.CLOSED);
assertThat(provider.paymentCount()).isEqualTo(1);     // once, not twice
```

The sweeper can ask "did this key happen?" only because the key was written **before** the effect.
Without that, reconciliation is a manual investigation.

---

## Compensation, with a window that is a number

```yaml
agentic.state-machine.settlement-delay: PT30M    # a timer, not a comment
```

Notification fails after payment → `COMPENSATING` → cancel inside the window → `FAILED`, money
returned:

```java
provider.failNextNotification();
RefundRun run = machine.start("A-1204", "Broken.").orElseThrow();

assertThat(run.state()).isEqualTo(RunState.FAILED);
assertThat(provider.paymentCount()).isZero();        // cancelled
assertThat(ledger.find(run.idempotencyKey()).orElseThrow().phase()).isEqualTo(Phase.COMPENSATED);
```

And the case people forget — the window has already closed
([`CompensationWindowClosedTest`](src/test/java/io/github/vuppalapatisn/agentic/statemachine/CompensationWindowClosedTest.java)):

```java
assertThat(run.state()).isEqualTo(RunState.NEEDS_MANUAL_INTERVENTION);
assertThat(provider.paymentCount()).isEqualTo(1);    // the money is still out — say so
```

A compensation that cannot run is an **incident**, not a retry.

---

## Run it

```bash
export ANTHROPIC_API_KEY=sk-ant-...
mvn spring-boot:run
```

```bash
curl -s localhost:8087/api/refunds/start -H 'Content-Type: application/json' \
  -d '{"orderId":"A-1187","message":"The parcel never arrived."}'

curl -s localhost:8087/api/approvals
curl -s -X POST localhost:8087/api/approvals/ap-1a2b3c4d/approve \
  -H 'Content-Type: application/json' -d '{"approver":"u-114"}'
curl -s localhost:8087/api/runs/r-1a2b3c4d/transitions
```

**Try the durability claim:** start a run on `A-1187`, stop the app (`Ctrl-C`), start it again, then
`GET /api/runs/{runId}` — the run is still `AWAITING_APPROVAL`, because the state is in
`./data/refund-runs.mv.db`. Do the same with project 06 and the approval is gone.

### Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/refunds/start` | begin a run |
| `GET` | `/api/runs/{id}` | current state |
| `GET` | `/api/runs/{id}/transitions` | the audit trail |
| `POST` | `/api/runs/{id}/advance` | resume after a restart or stall — idempotent |
| `GET` | `/api/approvals` | pending approvals with frozen amounts and expiries |
| `POST` | `/api/approvals/{id}/approve` \| `/reject` | the human decision |

---

## Tests — 18, no network

| Group | Asserts |
|-------|---------|
| Happy path | the full state sequence, every transition attributed, two-phase ledger entries |
| Durability | `AWAITING_APPROVAL` survives a re-read; `advance()` is a no-op; **the model is called once per run** |
| Approvals | frozen payload executes, duplicate refused, dual control needs two distinct approvers, rejection terminal |
| Expiry | sweeper → `EXPIRED`, nothing paid, and an expired approval cannot be approved afterwards |
| Reconciliation | lost acknowledgement → `PAID` once; confirmed-not-applied → `FAILED` with no blind retry |
| Compensation | inside the window → money returned; **window closed → `NEEDS_MANUAL_INTERVENTION`** |
| Transition guards | illegal transition throws; wrong expected state loses the race; terminal states are terminal |

---

## Cost of this architecture

Roughly 1.5–2× the code of [project 06](../06-workflow-orchestration/) for the same behaviour, plus
a database, a scheduler and migrations. Worth it the first time a pod restarts mid-approval, and not
before. The migration trigger is written down in
[06's CFG](../06-workflow-orchestration/docs/CFG.md#5-approval-points) so the decision is a record
rather than a preference.

**Next:** [08 — Autonomous agent loop](../08-autonomous-agent-loop/)
