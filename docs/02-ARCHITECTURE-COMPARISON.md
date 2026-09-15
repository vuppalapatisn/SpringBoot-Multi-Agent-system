# Workflow vs. State Machine vs. Agent

Four architectures, one job. Projects [06](../06-workflow-orchestration/),
[07](../07-state-machine-orchestration/), [08](../08-autonomous-agent-loop/) and
[09](../09-multi-agent-supervisor/) implement the **same Refund Desk** so the differences are real
code, not analogy.

The default should be the *least* dynamic architecture that does the job. Dynamism is not a feature;
it is a cost you pay in testability, cost variance, and incident surface.

---

## 1. The short answer

```
Are the steps and their order known at design time?
├─ yes ─▶ Does a run span minutes+, need durable pause, audit per transition, or compensation?
│         ├─ no  ─▶ WORKFLOW            (project 06)
│         └─ yes ─▶ STATE MACHINE       (project 07)
└─ no  ─▶ Can you name the specific runtime decision that code cannot make?
          ├─ no  ─▶ WORKFLOW. You do not need an agent.
          └─ yes ─▶ Do the steps split into specialities with different tools/authority?
                    ├─ no  ─▶ AGENT LOOP          (project 08)
                    └─ yes ─▶ MULTI-AGENT         (project 09)
```

The question that decides it: **"name the runtime decision that code cannot make."** If the answer is
a threshold, a lookup, a status check, or a regex, build a workflow with a model *inside* it. If the
answer is "which of 40 investigative paths is worth taking given what we just found", you have an
agent.

---

## 2. Definitions used here

| Architecture | Control lives in | The model's role |
|--------------|------------------|------------------|
| **Workflow** | code — a fixed DAG | node-level: classify, extract, summarise, score |
| **State machine** | code — explicit states + transitions, persisted | same, plus transition input |
| **Agent loop** | the model — it chooses the next tool until a stop condition | driver |
| **Multi-agent** | a supervisor (code or model) routing to specialists | driver per specialist, plus routing |

All four can contain LLM calls. The difference is **who chooses what happens next.**

---

## 3. Comparison matrix

| Dimension | Workflow | State machine | Agent loop | Multi-agent |
|-----------|----------|---------------|------------|-------------|
| Who picks the next step | code | code (transition table) | model | supervisor + model |
| Determinism | high | high | low | lowest |
| Agency budget (model-chosen edges) | 0 | 0 | n per step | n per step × agents |
| Latency | predictable, lowest | predictable + persistence I/O | variable (1–20× ) | highest |
| Cost per run | predictable | predictable | variable, needs a ceiling | highest variance |
| Token cost driver | one call per node | same | context regrows every step | context × agents + handoffs |
| Testability | unit-testable end to end | transition tests, replayable | needs eval harness + scripted models | needs eval harness per agent |
| Debuggability | stack trace | state history — best in class | trace of model decisions | distributed trace |
| Durable pause / resume | awkward (needs bolt-on) | **native** | awkward | via supervisor state |
| Human approval | static gate between nodes | **a state** — cleanest | runtime gate around the tool | gate + escalation contract |
| Compensation / saga | manual | **native** | hard — order is unknown | hard |
| Handles novel inputs | poorly | poorly | **well** | **well** |
| Handles wide scope | poorly | poorly | moderately | **well** |
| Blast radius on a bad model output | one node | one transition | whole run | whole run, wider credentials |
| Prompt-injection exposure | small, per node | small, per node | large — model drives tools | largest — plus agent-to-agent |
| Ops burden | low | medium (store, timers, migrations) | medium-high (budgets, evals) | high |
| Right for regulated flows | yes | **yes** | only with hard gates | rarely, needs strong contracts |
| Failure mode | node fails, stack trace | stuck state, visible in store | loops, budget exhaustion, drift | deadlock, ping-pong, cost blow-up |

---

## 4. Same job, four shapes

### 4.1 Workflow — project 06

```
[IN] ──▶ {lookupOrder} ──▶ {lookupPolicy} ──▶ {checkFraud} ──▶ (LLM classify)
      ──▶ <GATE policy> ──▶ [[STATE]] ──▶ {issueRefund}⚠ ──▶ {notify}⚠ ──▶ ((TERM))
```

Four composition patterns, all deterministic:

| Pattern | Where in project 06 |
|---------|---------------------|
| **Chain** | `RefundWorkflow` — sequential stages |
| **Parallel fan-out/fan-in** | policy lookup ∥ fraud check, joined before classification |
| **Route** | `IntentRouter` classifies, then code picks the branch |
| **Evaluator–optimiser** | draft customer message → critique → revise, max 2 rounds |

`agency = 0`. Cost is one classification call plus (for the message) up to three more. You can assert
the exact call sequence in a unit test.

**Choose it when** the process is a process. Most "AI agent" projects in enterprises are this, and
are better for being this.

**Limits** — a workflow cannot decide to investigate something you did not anticipate, and durable
pause has to be bolted on (the approval in project 06 is synchronous; if you need it to survive a
restart, you have discovered that you need project 07).

### 4.2 State machine — project 07

```
CREATED ──▶ ORDER_LOADED ──▶ POLICY_APPLIED ──▶ RISK_SCORED ──▶ DECIDED
   ├──▶ AWAITING_APPROVAL ──(approve)──▶ PAYOUT_PENDING ──▶ PAID ──▶ NOTIFIED ──▶ CLOSED
   │            ├──(reject)──▶ DECLINED          │
   │            └──(timeout)──▶ EXPIRED          └──(failure)──▶ COMPENSATING ⇠⇠ ──▶ FAILED
   └──▶ DECLINED
```

Everything that matters is explicit: states are rows, transitions are audited, the approval is a
*state* rather than a callback, timers drive expiry, and compensation is a first-class path.

**Choose it when** any of: runs outlast a request, a human has to approve and may take a day, you
need an audit record per transition, you need saga compensation, or a regulator will ask "what state
was this in at 14:02?".

**Cost** — a store, a scheduler, migrations, and the discipline of never mutating state outside a
transition. Roughly 1.5–2× the code of the workflow version for the same behaviour, and worth it the
first time a pod restarts mid-approval.

### 4.3 Agent loop — project 08

```
[IN] ──▶ [[STATE]] ──▶ ┌─(LLM)─╌╌▶ {tool}⟳ ─┐ ──▶ <GATE>⚠ ──▶ {issueRefund}⚠ ──▶ ((TERM))
                       └── ↺ max 8 steps / 90s / $0.50 / 12 tool calls ─┘
```

The model chooses the next tool. What makes it shippable is everything *around* the loop:

* `RunBudget` — steps, tool calls, tokens, cost, wall clock; exhaustion **fails closed** to
  `ESCALATED`, never "just pay it".
* Loop detection — same tool + same arguments twice in a row terminates the run.
* The gate is **outside** the model's reach: `GuardedToolExecutor` intercepts every irreversible
  call regardless of what the model asked for.
* Reflection step is bounded to one pass; unbounded self-critique is a cost leak, not a quality win.

**Choose it when** the investigation path genuinely depends on findings — fraud triage where each
signal suggests a different next check, or diagnostics over a large tool surface.

**Limits** — cost variance is the real problem (a 20× spread between p50 and p99 is normal), and a
single bad classification can send the whole run down a wrong path. Every irreversible action must
be gated by code, because the loop *will* eventually propose something wrong.

### 4.4 Multi-agent — project 09

```
                      ┌──▶ IntakeAgent      (R0 tools, no egress)
[IN] ──▶ (SUPERVISOR) ├──▶ PolicyAgent      (RAG, read-only)
              ▲       ├──▶ FraudAgent       (R1 tools, enum output only)
              │       └──▶ PayoutAgent      (E2 tools — gated, human-escalation rights)
              └── typed handoffs, per-agent budget, blackboard
```

The value is not "more intelligence". It is **separation of authority**: only `PayoutAgent` holds a
payment credential, and it has the smallest prompt surface and the narrowest tool list. A
prompt-injection success against `FraudAgent` cannot move money, because `FraudAgent` cannot.

Non-negotiables:

* **Typed handoff contracts** — a record in, a record out. Never free text between agents.
  Another agent's output is `R1` tainted input.
* **Per-agent budget** plus a run-level budget; the supervisor terminates on either.
* **Termination rules** — max handoffs, no A→B→A ping-pong, and a definite terminal state.
* **Least authority per agent**, enforced by which tool beans each agent is given.

**Choose it when** the work truly splits by speciality *and* authority. If the split is only
conceptual, you have added latency, cost and failure modes for nothing — use one agent with more
tools.

---

## 5. Cost and latency, measured shape

Relative numbers for the refund job (same model, same inputs). Treat the shape as the lesson, not
the absolute values.

| | Workflow | State machine | Agent loop | Multi-agent |
|---|---|---|---|---|
| Model calls / run (p50) | 1 | 1 | 4 | 7 |
| Model calls / run (p99) | 4 | 4 | 8 (budget cap) | 18 (budget cap) |
| Cost spread p50→p99 | 1.0 → 2.5× | 1.0 → 2.5× | 1.0 → **6×** | 1.0 → **9×** |
| Wall clock p50 | ~1.5 s | ~1.8 s | ~7 s | ~15 s |
| Extra infra | none | store + scheduler | budget accounting | + message/blackboard |
| LOC (this repo, main only) | ~900 | ~1,500 | ~1,100 | ~1,600 |

The p99 column is the one that decides architectures in production. A workflow's worst case is
knowable at design time. An agent's worst case is whatever you capped it at — which is exactly why
the cap is mandatory.

---

## 6. Hybrids (what you will actually ship)

The useful production shape is rarely pure.

| Hybrid | Shape | Use |
|--------|-------|-----|
| **Workflow with an LLM node** | deterministic DAG, model classifies inside one node | the 80% case. Start here. |
| **State machine with an agent inside one state** | FSM owns durability and approvals; one state runs a bounded agent loop | investigation inside a governed process |
| **Agent that emits a plan, workflow executes it** | model proposes a typed plan → validate → deterministic executor | best of both: flexible planning, auditable execution |
| **Supervisor as code, specialists as agents** | routing is a `switch`, not a model call | removes the highest-variance model decision from multi-agent |

The third row deserves emphasis: **plan-then-execute** removes the model from the execution path
entirely. The model's output is data that you validate, not control flow. When a design review
downgrades an "agent" to something safer, this is usually what it becomes.

---

## 7. Migration paths

| From | To | Trigger | Effort |
|------|----|---------|--------|
| Workflow | State machine | "the approver takes a day" / "a restart lost a run" | medium — extract states, add store |
| Workflow | Agent | "we keep adding branches and cannot enumerate them" | medium — but first try plan-then-execute |
| Agent | Workflow | "the tool sequence is the same 95% of the time" | **easy and usually a win** — codify the common path, keep the agent as fallback |
| Agent | Multi-agent | "one prompt now holds four jobs and all credentials" | high — define handoff contracts first |
| Multi-agent | Agent | "the specialists never disagree and always run in order" | easy — collapse and save the latency |

Downgrades are wins. A team that moved from agent to workflow because they learned the real path
shipped a cheaper, more testable system. Write that down in the ADR so nobody "upgrades" it back out
of fashion.

---

## 8. Checklist deltas per architecture

From [00 — Design Checklist](00-DESIGN-CHECKLIST.md), the phases that bite hardest:

| Architecture | Watch especially |
|--------------|------------------|
| Workflow | Phase 2 (tool classes), Phase 4 (gate placement between nodes) |
| State machine | Phase 4 (durable approvals), Phase 8 (compensation windows), plus: never mutate state outside a transition |
| Agent loop | Phase 7 (**every** budget), Phase 5 (taint — the model drives tools), Phase 9 (replay) |
| Multi-agent | Phase 6 (handoff contracts, least authority), Phase 7 (per-agent *and* run budgets), Phase 8 (deadlock/ping-pong) |

---

**Next:** [03 — Tool Boundaries](03-TOOL-BOUNDARIES.md) ·
[04 — Approvals & Irreversibility](04-APPROVALS-AND-IRREVERSIBILITY.md)
