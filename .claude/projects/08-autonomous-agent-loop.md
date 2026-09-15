# Brief — 08 Autonomous Agent Loop

**Purpose:** how to bound a model-driven loop. Note that `docs/CFG.md` marks Gate 6 as a **downgrade
candidate**: for this job project 06 is sufficient, and saying so is the point.

**Read first:** `08-autonomous-agent-loop/docs/CFG.md`.

**Start in:** `budget/RunBudget` — seven budgets, each exhaustion-tested.

**Do not break** (the first two hung the test suite when wrong)
- `BUDGET_ADVISOR_ORDER` stays **above** `ToolCallingAdvisor.DEFAULT_ORDER` (+300), or the advisor
  sees one turn per call and `maxSteps` never trips.
- `BudgetExceededException` stays in `rethrowExceptions`, or exhaustion becomes a message the model
  ignores and asks again forever.
- The budget travels in the advisor and tool context, never as a tool parameter.
- An unbudgeted run is refused outright.
- Every budget fails closed to ESCALATED.
- The outcome is derived from the gate's records, not from the agent's report.
- Reflection stays one pass and cannot call tools.

**Tests:** 18. If a test hangs, suspect the first two invariants before anything else.
