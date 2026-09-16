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
| 01 | [chatclient-foundation](01-chatclient-foundation/) | `ChatClient`, prompts, structured output, memory, advisors, observability | none (read-only) | Linear call | 14 |
| 02 | [tool-calling-guardrails](02-tool-calling-guardrails/) | tool boundary classes in code, tiered gate, frozen approvals, idempotency, dry-run, audit | `issueRefund`, `notifyCustomer` | Gated tool loop | 38 |
| 03 | [rag-grounded-answers](03-rag-grounded-answers/) | ingestion, vector store, `RetrievalAugmentationAdvisor`, groundedness gate, tenant isolation | none (read-only) | Retrieve → augment → **verify** | 19 |
| 04 | [mcp-server-tools](04-mcp-server-tools/) | MCP server, `@McpTool`, hints as a contract, server-side policy, separation of duty | `issueRefund` (approval-required) | Tool provider | 17 |
| 05 | [mcp-client-agent](05-mcp-client-agent/) | per-server trust, pinned definitions, rug-pull alarm, tool-poisoning scan | delegated over MCP | Client + gate | 15 |
| 06 | [workflow-orchestration](06-workflow-orchestration/) | deterministic DAG: chain, parallel, route, evaluator–optimiser | `issueRefund` behind a static gate | **Workflow** | 17 |
| 07 | [state-machine-orchestration](07-state-machine-orchestration/) | persisted FSM, durable approvals with expiry, reconciliation, saga compensation | `issueRefund` behind a durable gate | **State machine** | 18 |
| 08 | [autonomous-agent-loop](08-autonomous-agent-loop/) | seven budgets, loop detection, fail-closed exhaustion | `issueRefund` behind a runtime gate | **Agent** | 18 |
| 09 | [multi-agent-supervisor](09-multi-agent-supervisor/) | separation of authority, typed handoffs, termination rules | `issueRefund`, held by one agent only | **Multi-agent** | 26 |

Projects 06–09 solve the **identical** problem. Diff them. That is the point.

**182 tests, no network, no API key.** Every project's suite runs against a scripted `ChatModel`.

---

## Documentation

Read in this order.

| Doc | Why |
|-----|-----|
| [Architecture & Dataflow](docs/ARCHITECTURE.md) | **Start here for the shape of the system**: context, layering, component diagrams, DFDs with trust boundaries, key sequences, deployment. |
| [00 — Design Checklist](docs/00-DESIGN-CHECKLIST.md) | The reusable checklist. 10 phases, release gates, sign-off table. |
| [01 — Control-Flow Graph Method](docs/01-CONTROL-FLOW-GRAPH.md) | Notation, drawing rules, worked examples, anti-patterns. |
| [02 — Architecture Comparison](docs/02-ARCHITECTURE-COMPARISON.md) | Workflow vs state machine vs agent: decision matrix, cost, failure modes. |
| [03 — Tool Boundaries](docs/03-TOOL-BOUNDARIES.md) | The 7 boundary classes and the controls each one requires. |
| [04 — Approvals & Irreversibility](docs/04-APPROVALS-AND-IRREVERSIBILITY.md) | What makes an approval real. Idempotency, two-phase commit, compensation. |
| [05 — Observability & Evaluation](docs/05-OBSERVABILITY-AND-EVALS.md) | Traces, metrics, replay, offline evals, online guardrails. |
| [06 — Production Readiness](docs/06-PRODUCTION-READINESS.md) | The go-live gate: security, cost, SLOs, runbook. |
| [Templates](docs/templates/) | Blank CFG + design-review templates to copy into your own repos. |
| [Running with Docker](docs/RUNNING-WITH-DOCKER.md) | Step-by-step for Docker Desktop on macOS: every variable, the MCP pair, kill switches, troubleshooting. |

---

## Quick start

Prerequisites: **JDK 21+**, **Maven 3.9+**, and a key for one model provider.

```bash
export ANTHROPIC_API_KEY=sk-ant-...            # the default provider
```

**Google Gemini works too**, as a configuration change — no code edits:

```bash
export AI_CHAT_PROVIDER=google-genai
export GEMINI_API_KEY=AIza...                  # from aistudio.google.com/apikey
```

Only the selected provider's key is required. Both starters are on the classpath and
`spring.ai.model.chat` picks one; nothing in `src/main` imports a provider-specific type, because
every `ChatClient` is built with the neutral `ChatOptions.builder()`. Details and the property
mapping: [docs/RUNNING-WITH-DOCKER.md §2a](docs/RUNNING-WITH-DOCKER.md#2a-using-google-gemini-instead-of-anthropic).

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

**Project NN listens on port 808N** (01 → 8081 … 09 → 8089), so all nine can run side by side.

---

## Containers

Every project has its own `Dockerfile`, and the build context is just that project's directory —
each one declares `spring-boot-starter-parent` with an empty `<relativePath/>`, so it builds
standalone without the aggregator pom.

```bash
cd 06-workflow-orchestration
docker build -t agentic/workflow-orchestration .
docker run --rm -p 8086:8086 -e ANTHROPIC_API_KEY agentic/workflow-orchestration
```

Or the whole set, including the MCP server/client pair wired together:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
docker compose build
docker compose up 04-mcp-server 05-mcp-client      # client waits for the server to be healthy
```

**Step-by-step, including every value you need to supply:**
[docs/RUNNING-WITH-DOCKER.md](docs/RUNNING-WITH-DOCKER.md) — written for Docker Desktop on macOS
(Apple Silicon builds natively; both base images are multi-arch), and applicable to Linux and
Windows with the obvious substitutions.

There is exactly **one** variable to set: `ANTHROPIC_API_KEY`, needed by eight of the nine projects.

What the images do, and why:

| Choice | Reason |
|--------|--------|
| Multi-stage, `maven:3.9-eclipse-temurin-21-alpine` → `eclipse-temurin:21-jre-alpine` | no build tooling or source in the runtime image |
| `pom.xml` copied before `src` | the dependency layer is invalidated only when dependencies change |
| **Layered jar extraction** (`-Djarmode=tools … extract --layers`) | a code change re-pushes one thin layer, not every jar |
| Non-root `app` user | an agentic service holds provider credentials; least authority applies to the container too |
| `-XX:MaxRAMPercentage=75` | the JVM reads the cgroup limit, so one image behaves under any `--memory` |
| `exec java …` via `sh -c` | the JVM is PID 1 and gets `SIGTERM`, which is what makes graceful shutdown work |
| `HEALTHCHECK` on `/actuator/health` | busybox `wget` is already in the Alpine base, so no extra package |
| `ANTHROPIC_API_KEY` never baked in | passed at run time; a container started without it fails fast and says so |

Project **04 needs no API key** — an MCP server has no model in it. Project **07 mounts a volume**
at `/app/data`, because durable approvals surviving a restart is the property it exists to prove.

The kill switches are environment variables, so they can be flipped without a rebuild:

```bash
docker run --rm -p 8082:8082 -e ANTHROPIC_API_KEY \
  -e AGENTIC_TOOLS_EXECUTIONMODE=DRY_RUN agentic/tool-calling-guardrails
```

> Mind that name. Spring's relaxed binding replaces dots with underscores and **removes hyphens**,
> so `agentic.tools.execution-mode` is `AGENTIC_TOOLS_EXECUTIONMODE`.
> `AGENTIC_TOOLS_EXECUTION_MODE` binds to `agentic.tools.execution.mode`, which does not exist, and
> is ignored silently — a kill switch that quietly does nothing is worse than none.

### CI

| Workflow | Trigger | Does |
|----------|---------|------|
| [`build.yml`](.github/workflows/build.yml) | push, PR | `mvn clean verify` — all nine projects, 182 tests, no API key |
| [`docker.yml`](.github/workflows/docker.yml) | push, tag, PR, manual | builds an image **per changed project**, smoke-tests `/actuator/health`, publishes to GHCR on `main` |

`docker.yml` only builds the projects a commit actually touched (a change to the root pom or the
workflow itself builds all nine). Images are published as
`ghcr.io/vuppalapatisn/<artifactId>` using the built-in `GITHUB_TOKEN`, so **no secrets need
configuring**. Pull requests build and smoke-test but publish nothing.

The smoke test is the part worth keeping: it starts each image with a fake key and fails the build
unless `/actuator/health` reports `UP`. An image that cannot boot is not a built image, and CI is
much cheaper than a cluster for finding that out.

---

## Stack

| Component | Version | Note |
|-----------|---------|------|
| Java | 21 | toolchain-pinned per project |
| Spring Boot | 4.0.8 | required baseline for Spring AI 2.x |
| Spring AI | 2.0.1 | `ChatClient`, advisors, tools, RAG, MCP |
| MCP Java SDK | 2.0.0 | via `spring-ai-starter-mcp-*` |
| Default model | `claude-sonnet-5` | `claude-opus-5` for planner/supervisor roles |
| Alternative provider | Google Gemini | `spring-ai-starter-model-google-genai`, default `gemini-2.5-flash`; switch with one env var |
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
