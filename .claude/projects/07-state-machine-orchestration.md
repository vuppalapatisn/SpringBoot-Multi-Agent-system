# Brief — 07 State Machine Orchestration

**Purpose:** what you get when the run has somewhere to *be*. The most instructive diff in the repo
is 06 against this.

**Read first:** `07-state-machine-orchestration/docs/CFG.md`, then `domain/RunState.allowedNext()`.

**Start in:** `machine/RefundStateMachine` and `store/RunRepository.transition`.

**Do not break**
- The `state` column changes only through `RunRepository.transition` (guarded `UPDATE ... WHERE
  state = expected`). No other `UPDATE refund_run SET state` anywhere.
- Every transition names an actor and a reason: that is the audit trail.
- `PAYOUT_PENDING` stays. It is why a lost acknowledgement is recoverable.
- `recordIntent` before the provider call, `recordOutcome` after.
- Expiry means no; `consume` verifies the payload hash; no model turn after approval.
- `NEEDS_MANUAL_INTERVENTION` stays distinct from `FAILED` - it pages a human.

**Tests:** 18, in-memory H2. `spring.task.scheduling.enabled=false`; advance the clock before
asserting a sweep; `@BeforeEach` resets tables, the provider and the clock.
