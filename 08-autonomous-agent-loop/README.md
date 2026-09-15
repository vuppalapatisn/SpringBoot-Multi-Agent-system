# 08 — Autonomous Agent Loop

**What it teaches:** how to bound a model-driven loop so it is shippable. Seven budgets, loop
detection, a gate the model cannot reach around, and an outcome derived from what the gate recorded
rather than from what the agent says it did.

> Control-flow graph: [`docs/CFG.md`](docs/CFG.md) · Agency budget: **5** ·
> Irreversible actions: **`issueRefund`, `notifyCustomer`**

Same problem as [06](../06-workflow-orchestration/), [07](../07-state-machine-orchestration/) and
[09](../09-multi-agent-supervisor/).

---

## An honest note before the code

For **this** job, an agent is the wrong choice, and [project 06](../06-workflow-orchestration/)
proves it: the steps are known, so a workflow does the same work with `agency = 0` and an assertable
call sequence. [`docs/CFG.md`](docs/CFG.md) marks Gate 6 as a **downgrade candidate** on purpose.

An agent loop earns its place when the investigation path genuinely depends on findings — fraud
triage where each signal suggests a different next check, diagnostics over a wide tool surface. This
project exists to show how to bound one when you do need it.

---

## The loop is four lines. The engineering is around it.

```java
agentChatClient.prompt()
        .advisors(a -> a.param(BudgetAdvisor.RUN_BUDGET, budget))
        .tools(tools)
        .toolContext(Map.of(BudgetAdvisor.RUN_BUDGET, budget))
        .user(...)
        .call()
        .content();
```

Everything that matters is the seven budgets, the gate, and two configuration details that are easy
to get wrong (below).

---

## Seven budgets, each exhaustion-tested

| Budget | Limit | Why it exists |
|--------|-------|---------------|
| `STEPS` | 8 turns | the obvious one |
| `TOTAL_TOOL_CALLS` | 12 | a model can loop without adding turns |
| `PER_TOOL_CALLS` | 3; **1** for irreversible | the one-way door gets one attempt |
| `TOKENS` | 40,000 | context regrows every step |
| `COST` | \$0.50/run | trips before a runaway becomes an invoice |
| `WALL_CLOCK` | 90 s | the budget a waiting human feels |
| `NO_PROGRESS` | 2 identical calls | a model repeating itself will not converge |

All seven **fail closed** to `ESCALATED`:

```java
MODEL.thenRepeatForever("lookupOrder", "{\"orderId\":\"A-1204\"}");

AgentRunResult result = agent.handle("A-1204", "Where is my refund?");

assertThat(result.outcome()).isEqualTo(RunOutcome.ESCALATED);
assertThat(result.exhaustedBudget()).isEqualTo(Budget.NO_PROGRESS);
assertThat(guardedPayout.paymentCount()).isZero();
assertThat(result.toolCalls()).isLessThanOrEqualTo(2);      // stopped early, not at the ceiling
```

> **Running out of budget for checking never means doing the risky thing anyway.**

---

## Two configuration details that will bite you

Both were found by this project's tests hanging, and both are the kind of thing that looks fine in
review.

### 1. The budget advisor must be **inside** the tool-calling loop

`ToolCallingAdvisor` runs the loop, and *only advisors with a higher order participate in each
iteration*. Order the budget advisor below it and it sees **one turn per `call()`** — so `maxSteps`
never trips and a runaway model loops forever.

```java
// AgentConfig
static final int BUDGET_ADVISOR_ORDER = Ordered.HIGHEST_PRECEDENCE + 400;   // > +300
```

### 2. Budget exhaustion must not become a message the model can shrug off

By default Spring AI turns a tool exception into text handed back to the model. That is *right* for
a validation error — the model corrects itself and retries — and catastrophic for a budget: the
model is told "you are out of budget" and simply asks again, forever.

```java
@Bean
ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
    return DefaultToolExecutionExceptionProcessor.builder()
            .alwaysThrow(false)
            .rethrowExceptions(List.of(BudgetExceededException.class))   // makes the ceiling a ceiling
            .build();
}
```

---

## Three layers, protecting different things

| Layer | Protects against | Blind to |
|-------|------------------|----------|
| `spring.ai.tools.limits.*` | runaway loops, repeated calls | cost, wall clock, direct bean calls |
| `RunBudget` | cost, tokens, clock, no-progress, total steps | one catastrophic call |
| `GuardedPayout` | one catastrophic call | nothing — last line |

Not alternatives. All three. (One consequence shows up in the tests: the framework's belt can fire
*before* your budget, so `RefundAgentTest` raises the framework limits when it wants to exercise
`RunBudget` specifically.)

---

## The agent's claims are not evidence

An agent that reports a payment it did not make is a normal failure of this architecture, so the
outcome is derived from the **gate's records**:

```java
MODEL.thenCall("issueRefund", "{\"orderId\":\"A-1187\"}")
     .thenSay("I have refunded USD 240.00 to the customer. The money is on its way.");

assertThat(result.outcome()).isEqualTo(RunOutcome.ESCALATED);   // the gate suspended it
assertThat(result.receiptId()).isNull();
assertThat(guardedPayout.paymentCount()).isZero();
```

---

## Run it

```bash
export ANTHROPIC_API_KEY=sk-ant-...
mvn spring-boot:run
```

```bash
curl -s localhost:8088/api/refunds/run -H 'Content-Type: application/json' \
  -d '{"orderId":"A-1204","message":"The cable stopped working after two days."}'
```

Every response is a receipt:

```json
{ "outcome": "REFUNDED", "exhaustedBudget": null,
  "modelTurns": 5, "toolCalls": 4, "totalTokens": 1300,
  "estimatedCostMinor": 1, "elapsed": "PT6.2S",
  "trace": [ {"kind":"LLM"}, {"kind":"TOOL","detail":"lookupOrder"}, … ] }
```

Try the approval tier (`A-1187`), dual control (`A-0988`), and a rule refusal (`A-1310`).

---

## Tests — 18, no network

| Test class | Asserts |
|------------|---------|
| `RunBudgetTest` (9) | **every budget driven to exhaustion**, plus loop detection resetting on real progress and the trace being recorded |
| `RefundAgentTest` (9) | happy path with the full receipt; gate suspends above the tier; gate declines; **runaway stopped by loop detection**; per-tool ceiling; step ceiling; token/cost ceiling; **outcome from the gate not the claim**; unknown template refused |

`ScriptedChatModel.thenRepeatForever(...)` simulates a runaway without hoping a real model
misbehaves on cue.

---

## What you gave up versus project 06

| | 06 workflow | 08 agent |
|---|---|---|
| Call sequence | **asserted exactly** | not yours to promise |
| Model calls (p50 → cap) | 1 → 5 | 5 → 8 |
| Cost spread | ~2.5× | ~6× |
| Worst case | knowable at design time | **whatever you capped it at** |
| Handles novel inputs | poorly | well |

The last two rows are the trade. If you cannot name the runtime decision that justifies the second
column, take the first.

**Next:** [09 — Multi-agent supervisor](../09-multi-agent-supervisor/)
