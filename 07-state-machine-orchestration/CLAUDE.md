# CLAUDE.md — 07 State Machine Orchestration

Read [`docs/CFG.md`](docs/CFG.md) first. Repo-wide rules: [`../CLAUDE.md`](../CLAUDE.md).

## Entry points

| | |
|---|---|
| HTTP | `web/StateMachineController` |
| **The machine** | `machine/RefundStateMachine` ← start here |
| **The transition table** | `domain/RunState.allowedNext()` ← and here |
| Guarded transition | `store/RunRepository.transition` |
| Durable approvals | `store/ApprovalRepository` |
| Two-phase ledger | `store/EffectLedger` |
| Sweepers (expiry, reconciliation) | `machine/Sweepers` |
| Provider + compensation window | `effects/RefundProvider` |
| Schema | `src/main/resources/schema.sql` |

## Invariants

1. **The `state` column changes only through `RunRepository.transition`.** No `UPDATE refund_run SET
   state = …` anywhere else, ever. The guarded update is what makes concurrency safe.
2. **Every state change is in `RunState.allowedNext()`.** Adding a transition means editing that
   method and the CFG, in the same commit.
3. **Every transition names an actor and a reason.** They land in `run_transition`, which is the
   audit trail.
4. **`PAYOUT_PENDING` stays.** It is not a redundant intermediate state — it is the reason a lost
   acknowledgement is recoverable. Do not "simplify" `CLASSIFIED → PAID`.
5. **`recordIntent` before the provider call, `recordOutcome` after.** Two-phase execution, in that
   order.
6. **Expiry means no.** No auto-approve-on-timeout, ever.
7. **`consume` verifies the payload hash.** What executes must be what was approved.
8. **No model turn between approval and execution.** `approve()` must not re-classify. The test
   asserting one model call per run is there to catch that.
9. **`NEEDS_MANUAL_INTERVENTION` is distinct from `FAILED`.** Do not merge them; one pages a human.
10. **The compensation window is a stored timestamp** (`settles_at`), not a comment.

## Adding a state

1. Add it to `RunState` **and** to `allowedNext()` for every state that may reach it.
2. Update the `stateDiagram` in `docs/CFG.md`.
3. Handle it in `RefundStateMachine.advance()`'s switch — the switch is exhaustive on purpose.
4. Decide whether it is terminal, and whether a sweeper needs to act on it.
5. Add a test that reaches it and asserts the transition history.

## Tests

`mvn -q test` — 18 tests, no network, in-memory H2.

Two things that will bite you:

* **One application context per test class.** `@BeforeEach` clears all four tables **and** calls
  `provider.reset()` and `CLOCK.set(...)`. Forgetting the provider produces "expected 1 but was 2".
* **`spring.task.scheduling.enabled=false`** in tests, so sweepers run when a test calls them.
  Advance `CLOCK` past `reconcile-after` / `approval-ttl` before asserting a sweep.

`RefundProvider.failNextPayout(true)` simulates a **lost acknowledgement** (applied but reported as
failed); `failNextPayout(false)` simulates a genuine failure. The distinction is the point of the
reconciler.

## Known limitations

H2 not PostgreSQL (swap `spring.datasource`); single-node sweepers (a cluster needs a lock or a
leader); `run_transition` is append-only by convention here rather than by grant.
