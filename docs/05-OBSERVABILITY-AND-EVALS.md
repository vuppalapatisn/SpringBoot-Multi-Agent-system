# Observability & Evaluation

The operational question for an agentic system is not "is it up?" It is:

> **Why did run `r-8831` pay $240?**

You must be able to answer that from stored data, without re-running the model — because re-running
it will give a different answer, and because by then the evidence is gone.

---

## 1. The record model

Three levels, all keyed by `runId`.

| Level | Row per | Contains |
|-------|---------|----------|
| **Run** | request | id, tenant, actor, authority, architecture, terminal status, budgets consumed, cost, start/end |
| **Step** | node executed | seq, type (`LLM`/`TOOL`/`GATE`/`STATE`), name, duration, outcome |
| **Decision** | model call or gate | model+version, options, prompt hash, usage, finish reason \| gate rule, approver, payload hash |

```java
// project 06-09: DecisionLog — append-only, no UPDATE grant
record DecisionRecord(
    String runId, int seq, StepType type, String name,
    String inputHash, String outputSummary,     // redacted
    String model, ChatUsage usage, Duration took,
    String gateRule, String approver, String payloadHash,
    Instant at) {}
```

Rules:

* **`runId` on every log line, span, and record.** Propagate it through MDC and as a tool-context
  entry so tool implementations can log it too.
* **Redact at write time**, not at read time. PII that reaches the log has already leaked.
* **Hash prompts, don't always store them.** Store the hash always; store the full prompt under a
  sampling policy and a retention limit. `spring.ai.chat.client.observations.log-prompt` exists but
  is a **debug** setting — it puts prompt content in logs.
* **Store the model's raw structured output** for every decision that led to an effect. This is the
  single most useful artefact in a post-incident review.

---

## 2. Spring AI instrumentation

Spring AI 2.x emits Micrometer observations for chat clients, advisors, tool calls and vector
stores. Turn them on and add the run dimension.

```yaml
spring:
  ai:
    chat:
      client:
        observations:
          log-prompt: false          # debug only — prompt content in logs
          log-completion: false
    tools:
      observations:
        include-content: false       # tool args/results can hold PII
management:
  endpoints.web.exposure.include: health,info,metrics,prometheus
  metrics.tags.application: ${spring.application.name}
  tracing.sampling.probability: 1.0
```

| Signal | Source |
|--------|--------|
| `gen_ai.client.operation` | model call duration, model name, tokens |
| `spring.ai.advisor` | per-advisor duration — shows retrieval vs. generation split |
| `spring.ai.tool.call` | tool name, duration, errors |
| `db.vector.client.operation` | vector store query latency and returned-doc count |

Add your own, because the framework cannot know your business invariants:

| Metric | Type | Alert on |
|--------|------|----------|
| `agentic.run.duration` | timer, tagged by architecture + terminal status | p99 drift |
| `agentic.run.cost` | distribution summary (currency) | p99 near ceiling |
| `agentic.run.steps` | distribution summary | rising p50 = drift |
| `agentic.budget.exhausted` | counter, tagged by budget | any increase |
| `agentic.gate.decision` | counter, tagged `auto|approved|rejected|expired` | **rejection-rate change** |
| `agentic.tool.irreversible` | counter, tagged tool | volume vs. forecast |
| `agentic.approval.pending.age` | gauge | oldest pending > SLA |
| `agentic.taint.validator.rejected` | counter | spike = probing |

The two most valuable of these are the ones people forget: **approval rejection rate** (a rise means
the model's judgement has drifted, or someone is probing it) and **budget-exhaustion rate** (a rise
means the loop stopped converging).

Alert on *change*, not absolute value. "3% of refunds escalate" is meaningless; "escalations went
from 3% to 19% after the prompt change at 14:02" is an incident.

---

## 3. Tracing an agentic run

One trace per run, one span per step, model calls as child spans.

```
run r-8831 ─────────────────────────────────────────────── 6.4s  REFUNDED
├─ step 1  TOOL  lookupOrder            R0      12ms
├─ step 2  TOOL  lookupPolicy           R0       8ms
├─ step 3  TOOL  checkFraud             R1     310ms   provenance=partner:sift
├─ step 4  LLM   classify               claude-sonnet-5  1.9s  in=2,411 out=96  stop=end_turn
│   └─ output: {"outcome":"REFUND","amountMinor":24000,"risk":"LOW","reason":"..."}
├─ step 5  GATE  policy                  rule=amount>100 → needs_human
├─ step 6  STATE AWAITING_APPROVAL       hash=9f2c…      expires=2026-09-17T09:12Z
├─ step 7  GATE  human                   approver=u-114  hash=9f2c… ✓ match
├─ step 8  STATE intent recorded         idem=4a81…
├─ step 9  TOOL  issueRefund            E2 ⚠  840ms   receipt=re_9Q…  idem=4a81…
└─ step 10 TOOL  notifyCustomer         E2 ⚠  220ms
```

Read that trace and you can answer the $240 question in one glance: the model classified, the policy
gate demanded a human, `u-114` approved a payload whose hash matches what executed.

Note what is *not* in the trace: the customer's message body, the card number, the email content.
Those are referenced by hash and stored under retention policy.

---

## 4. Replay

Replay is reconstruction for review, not re-execution.

| Mode | What it does | Use |
|------|--------------|-----|
| **Reconstruct** | render the stored run as the trace above | post-incident, audit, dispute |
| **Shadow replay** | re-run with tools in `DRY_RUN` and the recorded inputs | "would the new prompt have done the same?" |
| **Regression replay** | shadow-replay a corpus of past runs against a candidate change | pre-deploy gate |

Shadow replay is the highest-leverage test you can build for an agentic system, and it is nearly free
once tools have a dry-run mode: take 200 real runs, replay them against the new prompt/model, diff
the decisions, and look at every disagreement on an irreversible action.

Requirements for replay to work: stored inputs (hashed or full per policy), stored model outputs,
pinned model version, and a `DRY_RUN` mode on every effect. All four exist in this repo.

---

## 5. Offline evaluation

Minimum eval set for any agentic feature — small, but every row earns its place:

| Category | Rows | Asserts |
|----------|------|---------|
| Happy path | 10–30 | correct outcome and amount |
| Boundary | one per threshold (`$99.99`, `$100.00`, `$100.01`, day 30 / 31) | the **gate** fires correctly, not just the model |
| Ambiguous | 5–10 | escalates rather than guessing |
| Adversarial | 10+ | prompt injection in the customer message, in a retrieved doc, in a fraud-provider response, in an MCP tool description |
| Irreversible | **one per irreversible action** | no execution without a token; gate decision recorded |
| Malformed | 5 | unparseable model output → defined path, not a 500 |
| Budget | one per budget | exhaustion → correct fail-closed terminal state |

Run them with a scripted `ChatModel` for determinism (see `ScriptedChatModel` in every project's
test sources) and with the real model on a schedule, because the two catch different things:
scripted tests catch *your* regressions, live evals catch *model* drift.

Grading:

| Type | Method | Note |
|------|--------|------|
| Structural | assert on the typed output / terminal state | preferred — deterministic |
| Reference | exact or fuzzy match against expected | good for extraction |
| LLM-as-judge | a model grades a rubric | only for prose quality; never for whether money should move |

**Never let an LLM judge be the gate on an irreversible action.** Judges are for measuring tone and
helpfulness. Thresholds are for money.

---

## 6. Online guardrails

| Guardrail | Action on breach |
|-----------|------------------|
| Per-run cost ceiling | terminate run, `ESCALATED` |
| Tenant hourly cost | shed load, alert |
| Irreversible-action rate per tenant | throttle, then `DRY_RUN` |
| Approval rejection rate > threshold | page — the model's judgement changed |
| Tool error rate per tool | circuit-break that tool |
| Taint-validator rejections spike | page — probable probing |
| Model p99 latency | fall back to degraded mode |

And the one that is not a metric: a **kill switch** (`agentic.tools.execution-mode=DRY_RUN`) that an
on-call engineer can flip without a deploy, plus a documented procedure to revoke the agent's
credentials at the provider.

---

## 7. Checklist

- [ ] `runId` propagated through logs, spans, records, and tool context.
- [ ] Run / step / decision records persisted append-only, redacted at write.
- [ ] Model name **and version** recorded per call; prompts versioned as artefacts.
- [ ] Micrometer observations enabled; content logging **off** in production.
- [ ] Custom metrics for cost, budget exhaustion, gate decisions, irreversible actions.
- [ ] Alerts on rate-of-change, not just thresholds.
- [ ] Reconstruct-replay works from stored data alone.
- [ ] Shadow replay available; used as a pre-deploy gate.
- [ ] Eval set covers happy / boundary / ambiguous / adversarial / irreversible / malformed / budget.
- [ ] Kill switch documented and tested.

---

**Next:** [06 — Production Readiness](06-PRODUCTION-READINESS.md)
