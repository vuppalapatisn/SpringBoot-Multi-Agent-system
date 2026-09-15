# Spring Boot Agentic AI Reference — CFG-First Agent Design

A reference implementation of **production-grade agentic systems on Spring Boot 4 + Spring AI 2.0**,
built around one discipline:

> Before you implement a `ChatClient`, tool calling, RAG, MCP, or multi-agent orchestration,
> **draw the control-flow graph first** and name the **irreversible actions**, **approval points**
> and **tool boundaries**. Only then write code.

That discipline is not a slogan here. It is:

* a [reusable design checklist](docs/00-DESIGN-CHECKLIST.md) with release gates,
* a [control-flow-graph notation](docs/01-CONTROL-FLOW-GRAPH.md) you can draw on a whiteboard in 10 minutes,
* a [decision matrix](docs/02-ARCHITECTURE-COMPARISON.md) for **workflow vs. state machine vs. agent**,
* a Claude Code [skill](.claude/skills/agentic-design-review/SKILL.md) that runs the checklist for you,
* and **nine runnable Spring Boot projects** where every design artefact maps to real code.

---

## The one domain, nine times

Every project implements the same business domain — a **Refund Desk** — so you can compare
architectures instead of comparing toy examples.

> A customer asks for a refund. The system must read the order, apply refund policy,
> check fraud signals, then either decline, refund, or escalate to a human.
> **Issuing a refund moves money and cannot be undone.** Emailing the customer cannot be unsent.

That single irreversible action (`issueRefund`) is what forces every interesting design decision in
this repo: approval gates, idempotency keys, durable checkpoints, compensation windows, budget
ceilings, and the choice of architecture.

---

## Projects

| # | Project | What it teaches | Irreversible actions | Architecture | Tests |
|---|---------|-----------------|----------------------|--------------|-------|
| 01 | [chatclient-foundation](01-chatclient-foundation/) | `ChatClient`, prompts, structured output, memory, advisors, observability | none (read-only) | Linear call | 10 |
| 02 | [tool-calling-guardrails](02-tool-calling-guardrails/) | tool boundary classes in code, tiered gate, frozen approvals, idempotency, dry-run, audit | `issueRefund`, `notifyCustomer` | Gated tool loop | 38 |
| 03 | [rag-grounded-answers](03-rag-grounded-answers/) | ingestion, vector store, `RetrievalAugmentationAdvisor`, groundedness gate, tenant isolation | none (read-only) | Retrieve → augment → **verify** | 19 |
| 04 | [mcp-server-tools](04-mcp-server-tools/) | MCP server, `@McpTool`, hints as a contract, server-side policy, separation of duty | `issueRefund` (approval-required) | Tool provider | 17 |
| 05 | [mcp-client-agent](05-mcp-client-agent/) | per-server trust, pinned definitions, rug-pull alarm, tool-poisoning scan | delegated over MCP | Client + gate | 15 |
| 06 | [workflow-orchestration](06-workflow-orchestration/) | deterministic DAG: chain, parallel, route, evaluator–optimiser | `issueRefund` behind a static gate | **Workflow** | 17 |
| 07 | [state-machine-orchestration](07-state-machine-orchestration/) | persisted FSM, durable approvals with expiry, reconciliation, saga compensation | `issueRefund` behind a durable gate | **State machine** | 18 |
| 08 | [autonomous-agent-loop](08-autonomous-agent-loop/) | seven budgets, loop detection, fail-closed exhaustion | `issueRefund` behind a runtime gate | **Agent** | 18 |
| 09 | [multi-agent-supervisor](09-multi-agent-supervisor/) | separation of authority, typed handoffs, termination rules | `issueRefund`, held by one agent only | **Multi-agent** | 26 |

Projects 06–09 solve the **identical** problem. Diff them. That is the point.

**178 tests, no network, no API key.** Every project's suite runs against a scripted `ChatModel`.

---

## Documentation

Read in this order.

| Doc | Why |
|-----|-----|
| [00 — Design Checklist](docs/00-DESIGN-CHECKLIST.md) | The reusable checklist. 10 phases, release gates, sign-off table. |
| [01 — Control-Flow Graph Method](docs/01-CONTROL-FLOW-GRAPH.md) | Notation, drawing rules, worked examples, anti-patterns. |
| [02 — Architecture Comparison](docs/02-ARCHITECTURE-COMPARISON.md) | Workflow vs state machine vs agent: decision matrix, cost, failure modes. |
| [03 — Tool Boundaries](docs/03-TOOL-BOUNDARIES.md) | The 7 boundary classes and the controls each one requires. |
| [04 — Approvals & Irreversibility](docs/04-APPROVALS-AND-IRREVERSIBILITY.md) | What makes an approval real. Idempotency, two-phase commit, compensation. |
| [05 — Observability & Evaluation](docs/05-OBSERVABILITY-AND-EVALS.md) | Traces, metrics, replay, offline evals, online guardrails. |
| [06 — Production Readiness](docs/06-PRODUCTION-READINESS.md) | The go-live gate: security, cost, SLOs, runbook. |
| [Templates](docs/templates/) | Blank CFG + design-review templates to copy into your own repos. |

---

## Quick start

Prerequisites: **JDK 21+**, **Maven 3.9+**, and an Anthropic API key.

```bash
export ANTHROPIC_API_KEY=sk-ant-...
```

Build everything (no API key needed — tests use a scripted `ChatModel`):

```bash
mvn -q clean verify
```

Run one project:

```bash
cd 01-chatclient-foundation && mvn spring-boot:run
```

Each project's `README.md` lists its endpoints, its control-flow graph, and its
irreversible-action catalogue.

---

## Stack

| Component | Version | Note |
|-----------|---------|------|
| Java | 21 | toolchain-pinned per project |
| Spring Boot | 4.0.8 | required baseline for Spring AI 2.x |
| Spring AI | 2.0.1 | `ChatClient`, advisors, tools, RAG, MCP |
| MCP Java SDK | 2.0.0 | via `spring-ai-starter-mcp-*` |
| Default model | `claude-sonnet-5` | `claude-opus-5` for planner/supervisor roles |
| Observability | Micrometer + Actuator | `spring.ai.chat.client.observations.*` |

> Spring AI 2.x moved options to builders (`ChatOptions.Builder`), moved tool execution into
> `ToolCallingAdvisor`, and added first-class tool-call limits (`spring.ai.tools.limits.*`).
> The code here targets that API, not the 1.x API.

---

## Working with Claude Code in this repo

* [`CLAUDE.md`](CLAUDE.md) — repo conventions the agent must follow.
* [`.claude/skills/agentic-design-review/SKILL.md`](.claude/skills/agentic-design-review/SKILL.md) — runs the checklist against a diff or design.
* [`.claude/skills/spring-ai-scaffold/SKILL.md`](.claude/skills/spring-ai-scaffold/SKILL.md) — scaffolds a new CFG-first Spring AI service.
* [`.claude/projects/00-index.md`](.claude/projects/00-index.md) — per-project briefs **and the
  Spring AI 2.x trap list**: the three configuration mistakes that make a tool loop silently do
  nothing, hang, or ignore its own budget. Read it before debugging one.
* [`.claude/commands/`](.claude/commands/) — `/design-check` and `/cfg`.

---

## License

Apache-2.0. See [LICENSE](LICENSE).
