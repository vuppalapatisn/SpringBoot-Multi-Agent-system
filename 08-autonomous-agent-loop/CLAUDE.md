# CLAUDE.md — 08 Autonomous Agent Loop

Read [`docs/CFG.md`](docs/CFG.md) first. Repo-wide rules: [`../CLAUDE.md`](../CLAUDE.md).

## Entry points

| | |
|---|---|
| HTTP | `web/AgentController` |
| The loop | `service/RefundAgent` |
| **The budgets** | `budget/RunBudget` ← start here |
| Step/token counting | `budget/BudgetAdvisor` |
| **The gate** | `gate/GuardedPayout` |
| Tools | `tools/RefundAgentTools` |
| Wiring, incl. two load-bearing details | `config/AgentConfig` |

## Invariants

1. **`BUDGET_ADVISOR_ORDER` stays above `ToolCallingAdvisor.DEFAULT_ORDER`** (`HIGHEST_PRECEDENCE +
   300`). Below it, the advisor sees one turn per `call()`, `maxSteps` never trips, and a runaway
   model loops forever. This is not a style preference — it hung this project's own test suite.
2. **`BudgetExceededException` stays in `rethrowExceptions`.** If it is converted into a tool
   message, the model is told it is out of budget and asks again, forever.
3. **The budget travels in the advisor context and the tool context, never as a tool parameter.**
   The model must not be able to influence the thing that limits it.
4. **An unbudgeted run is refused** (`IllegalStateException` in both `BudgetAdvisor` and the tools).
   Do not add a permissive default.
5. **Every budget fails closed to `ESCALATED`.** Never add a path where exhaustion proceeds.
6. **`GuardedPayout` is not an advisor** and must not become one.
7. **No tool gains an amount or a recipient parameter.**
8. **The outcome is derived from the gate's records**, not from the agent's report.
   `classifyOutcome` must keep reading `GuardedPayout`, not parsing the reply.
9. **Reflection stays one pass and cannot call tools.** Unbounded self-critique is a cost leak.

## Adding a budget

1. Add a constant to `Budget`, a limit to `AgentProperties`, and the check to `RunBudget`.
2. Add the exhaustion test to `RunBudgetTest` — Phase 7 requires one per budget.
3. Record it in `docs/CFG.md` §2 with its enforcement point and exhaustion behaviour.

## Adding a tool

1. Update `docs/CFG.md` (boundary table + agency budget — it goes up by one).
2. `budget.beforeToolCall(name, signature)` **first**, where the signature includes the arguments
   (loop detection depends on it).
3. Identifiers only; validate everything; irreversible effects go through `GuardedPayout`.
4. Add a `max-calls-per-tool` entry in both `agentic.agent` and `spring.ai.tools.limits`.

## Tests

`mvn -q test` — 18 tests, no network.

* `ScriptedChatModel` **must** override `getOptions()` to return `ToolCallingChatOptions`, or the
  tool loop silently skips.
* `thenRepeatForever(...)` simulates a runaway model.
* `@BeforeEach` resets the model, `GuardedPayout` and the clock — one context per class.
* `RefundAgentTest` raises `spring.ai.tools.limits.*` so that `RunBudget` is the layer under test.
  In production both are configured and either may fire first.
* If a test hangs, suspect invariants 1 and 2 before anything else.
