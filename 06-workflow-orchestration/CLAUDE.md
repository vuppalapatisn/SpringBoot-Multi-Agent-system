# CLAUDE.md — 06 Workflow Orchestration

Read [`docs/CFG.md`](docs/CFG.md) first. Repo-wide rules: [`../CLAUDE.md`](../CLAUDE.md).
Compare with [07](../07-state-machine-orchestration/), [08](../08-autonomous-agent-loop/),
[09](../09-multi-agent-supervisor/) — same problem, four architectures.

## Entry points

| | |
|---|---|
| HTTP | `web/WorkflowController` |
| **The DAG** | `orchestration/RefundWorkflow` ← start here |
| Stage 1–2 (parallel) | `steps/FactGathering` |
| Stage 3 (the only LLM node) | `steps/CaseClassifier` |
| Stage 4 (gate) | `gate/PolicyGate` |
| Stage 6 (evaluator–optimiser) | `steps/ReplyDrafter` |
| Effects | `effects/RefundEffects` |
| Approvals | `gate/ApprovalDesk` |

## Invariants

1. **Agency budget stays 0.** No model chooses a step, a branch or a tool. If a change would let
   it, that change belongs in project 08.
2. **The step list stays exact.** `RefundWorkflowTest` pins `result.steps()`. If a test fails
   because the sequence changed, update the CFG in the same commit — do not loosen the assertion to
   `contains`.
3. **`ReplyDrafter.MAX_ROUNDS` stays a constant** and the deterministic forbidden-phrase check runs
   **after** the critic. The critic is advisory; the rule decides.
4. **Effect ordering: pay, then notify.** The least reversible effect goes last.
5. **`reconcile()` stays** in `CaseClassifier`: unknown clause → `FABRICATED_CLAUSE`, amount
   mismatch → `AMOUNT_MISMATCH`, both escalate with the order total applying.
6. **The gate reads only trusted values** — order record and the fraud enum. Never a model string.
7. **Fraud degrades, policy fails.** Advisory data falls back to `UNAVAILABLE` (which is not LOW,
   so it cannot auto-approve); authoritative data failing fails the run.
8. **No expiry, no durability — on purpose.** Do not bolt a scheduler onto `ApprovalDesk`. If the
   requirement appears, the answer is project 07's architecture, and that is a design decision, not
   a patch.

## Adding a stage

1. Update `docs/CFG.md` — node, class, and the step name.
2. Add the step to `RefundWorkflow` and append its name to `steps`.
3. Update the `containsExactly` assertions. They are the specification.
4. If the stage is an effect, route it through `RefundEffects` with an idempotency key and dry-run.

## Tests

`mvn -q test` — 17 tests, no network. One `ScriptedChatModel` queue is shared by the classifier and
the drafter, which is what makes the call *order* assertable; enqueue responses in the order the
workflow will consume them.

Use the injected `Clock`; `FactGathering` seeds order dates relative to it.
