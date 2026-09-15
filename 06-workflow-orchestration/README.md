# 06 — Workflow Orchestration

**What it teaches:** the architecture most "AI agent" projects should actually be. A fixed DAG in
ordinary Java, with the model confined to one classification node — and all four workflow
composition patterns in one readable service.

> Control-flow graph: [`docs/CFG.md`](docs/CFG.md) · Agency budget: **0** ·
> Irreversible actions: **`issueRefund`, `notifyCustomer`**

First of four projects solving the **same** problem:
[06 workflow](.) · [07 state machine](../07-state-machine-orchestration/) ·
[08 agent loop](../08-autonomous-agent-loop/) · [09 multi-agent](../09-multi-agent-supervisor/).
**Diff them.** That is the point.

---

## The property you are buying

```java
assertThat(result.steps()).containsExactly(
        "loadOrder",
        "gatherFacts(policy∥fraud)",
        "classify",
        "gate:AUTO_LOW_VALUE_LOW_RISK",
        "issueRefund",
        "draftReply(rounds=1)",
        "notifyCustomer");
```

**The exact call sequence is assertable.** Every test in this project pins it. An agent loop cannot
offer that, and giving it up is the real cost of choosing one.

---

## The four patterns

| Pattern | Where | Note |
|---------|-------|------|
| **Chain** | `RefundWorkflow.run` | plain sequential Java; the graph *is* the code |
| **Parallel fan-out/fan-in** | `FactGathering.gather` | policy ∥ fraud on virtual threads, 3 s branch timeout, deterministic join |
| **Routing** | `switch (gate.outcome())` | exhaustive over an enum, so no branch can be forgotten |
| **Evaluator–optimiser** | `ReplyDrafter` | draft → critique → revise, **max 2 rounds** |

The parallel stage is a win an agent architecture cannot make: it does not know in advance that the
two lookups are independent. A workflow does, because you told it.

### The evaluator–optimiser, done safely

Three things make it a workflow pattern rather than an agent:

* the bound is a constant (`MAX_ROUNDS = 2`), not a model decision;
* the critic returns a machine-checkable verdict (`OK` / `REVISE: …`), so the exit condition is a
  string comparison;
* **a deterministic rule runs after the critic.** If the final draft contains a forbidden phrase,
  it is replaced with a safe template — the run never emits an unvetted message:

```java
model.enqueue(classification(...),
              "We guarantee your money back immediately.",   // draft
              "OK");                                         // the critic wrongly approves it

assertThat(result.customerReply()).doesNotContain("guarantee");
```

An LLM critic measures tone. A rule decides whether a forbidden promise reached a customer.

---

## Where the model is, and is not

One LLM node — `CaseClassifier` — reads the messy complaint and returns a record. Then **code**
decides, and two reconciliations run before the gate:

| Check | Failure verdict |
|-------|-----------------|
| Cited clause was among those supplied | `FABRICATED_CLAUSE` → escalate |
| Proposed amount equals the order total | `AMOUNT_MISMATCH` → escalate, order total applies |

And the gate outranks the model entirely:

```java
// the model says REFUND on an in-transit order
assertThat(result.gateRule()).isEqualTo("ORDER_NOT_DELIVERED");
assertThat(result.status()).isEqualTo(RunStatus.DECLINED);
```

---

## Where this architecture runs out

`ApprovalDesk` carries a comment you should read before copying this design:

> A workflow is a function call: it runs to completion and returns. It has nowhere to *be* while a
> human takes a day to decide.

So a pending approval is parked in a side store and picked up by an **unrelated second request**.
Consequences, all visible in [`docs/CFG.md`](docs/CFG.md) §5:

* the approval is not durable — a restart loses it;
* there is no expiry timer;
* there is no run state to resume, and no audit of what happened between the halves.

If approvals are routine rather than exceptional, the design has outgrown a workflow. That is the
migration trigger, and [project 07](../07-state-machine-orchestration/) is the destination.

---

## Run it

```bash
export ANTHROPIC_API_KEY=sk-ant-...
mvn spring-boot:run
```

```bash
# automatic tier — $89.90, low risk, 3 days old
curl -s localhost:8086/api/refunds/run -H 'Content-Type: application/json' \
  -d '{"orderId":"A-1204","message":"The cable stopped working after two days."}'
```

```bash
# approval tier — $240
curl -s localhost:8086/api/refunds/run -H 'Content-Type: application/json' \
  -d '{"orderId":"A-1187","message":"The parcel never arrived."}'

curl -s localhost:8086/api/approvals
curl -s -X POST localhost:8086/api/approvals/ap-1a2b3c4d/approve \
  -H 'Content-Type: application/json' -d '{"approver":"u-114"}'
```

Orders: `A-1204` auto · `A-1187` single approver · `A-0988` dual control · `A-1310` declined
(in transit).

---

## Tests — 17, no network

| Test | Asserts |
|------|---------|
| `automaticTierRunsTheWholeChain` | **the exact step sequence**, one payment, one notification, 3 model calls |
| `approvalTierSuspends` | nothing paid, nothing said, **only 1 model call** — no prose is drafted before a human decides |
| `dualControlTier` | two approvers required |
| `gateOverridesTheModel` | in-transit declined however it was classified |
| `amountMismatchEscalates` | order total wins |
| `fabricatedClauseEscalates` | invented clause id escalates |
| `unparseableOutputEscalates` | fails closed, not a 500 |
| `evaluatorOptimiserRevisesOnce` | one revision, 5 model calls, amount removed |
| `deterministicCheckBeatsTheCritic` | forbidden phrase → safe template |
| `approvalResumePaysOnce` | duplicate resume pays nothing more |
| `dualControlNeedsTwoApprovers` | same approver twice refused |
| `dryRunChangesNothing` | kill switch |
| `unknownOrder` | 404 with **zero** model calls |
| `FactGatheringTest` (4) | fan-out/fan-in, fraud degradation, `R1` → enum, id validation |

**Next:** [07 — State machine orchestration](../07-state-machine-orchestration/)
