# Brief — 06 Workflow Orchestration

**Purpose:** the architecture most "AI agent" projects should actually be. First of four solving the
same problem; diff it against 07, 08 and 09.

**Read first:** `06-workflow-orchestration/docs/CFG.md`.

**Start in:** `orchestration/RefundWorkflow` — the DAG is the code.

**Do not break**
- Agency budget stays 0. No model chooses a step, a branch or a tool.
- `RefundWorkflowTest` pins `result.steps()` with `containsExactly`. That assertion is the
  specification; if the sequence changes, update the CFG in the same commit.
- `ReplyDrafter.MAX_ROUNDS` stays a constant, and the deterministic forbidden-phrase check runs
  after the critic.
- Effect order: pay, then notify. The least reversible effect goes last.
- Fraud degrades to UNAVAILABLE; policy failing fails the run.
- No expiry, no durability - on purpose. If you need them, that is project 07's architecture.

**Tests:** 17.
