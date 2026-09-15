# CLAUDE.md — repo conventions

Reference implementation of CFG-first agentic design on **Spring Boot 4.0.8 + Spring AI 2.0.1**,
nine standalone Maven projects over one domain (Refund Desk).

## The rule that overrides convenience

**Before implementing or changing a `ChatClient`, tool, RAG pipeline, MCP surface, or orchestration
path: read the project's `docs/CFG.md` first, and update it in the same change.**

If a change adds a tool, a model-chosen edge, a loop, or an effect, the CFG and the
irreversible-action catalogue are part of the diff. A PR that changes behaviour without touching
`docs/CFG.md` is incomplete.

Run `/design-check` (see `.claude/commands/`) or the `agentic-design-review` skill before opening a PR.

## Non-negotiable invariants

These are enforced by tests and, where possible, by startup validation. Do not work around them.

1. **Every `@Tool` / `@McpTool` method carries `@ToolBoundary`.** Startup fails otherwise.
2. **Irreversible tools are unreachable without an approval token.** The gate lives in
   `GuardedToolExecutor` (inside the tool boundary), never in an advisor — advisors wrap the model
   call and are bypassable by direct bean calls.
3. **Idempotency keys are derived by the system** from `runId` + business key. Never from model
   output, never random per attempt.
4. **The model never supplies a value that can be looked up.** Refund amounts come from the order
   record; the model's proposal is compared and a mismatch is a decline, not an escalation.
5. **Every loop has a numeric bound and a fail-closed exhaustion path** (`ESCALATED`, never "pay it
   anyway").
6. **A durable checkpoint precedes every irreversible effect.**
7. **Untrusted content is labelled as data** and never concatenated into a system message.

## Code conventions

* Java 21, records for DTOs, `sealed` where the variant set is closed, no Lombok.
* Constructor injection only. No field `@Autowired`. Components are package-private where possible.
* `@ConfigurationProperties` records for config; no `@Value` scattered through beans.
* Spring AI 2.x API specifics (different from 1.x — do not copy 1.x snippets):
  - options are **builders**: `.options(AnthropicChatOptions.builder().temperature(0.0))`
  - tool execution runs through `ToolCallingAdvisor`; framework limits via `spring.ai.tools.limits.*`
  - `ToolCallbacks.from(bean)` lives in `org.springframework.ai.support`
  - the vector-store advisor artifact is `spring-ai-vector-store-advisor`
  - MCP annotations are `org.springframework.ai.mcp.annotation.@McpTool` / `@McpToolParam`
* Prompts live in `src/main/resources/prompts/*.st` — never inline multi-line strings in Java.
* One package per CFG concern: `web`, `domain`, `tools`, `gate`, `budget`, `audit`, `orchestration`.

## Testing conventions

* Unit tests use `ScriptedChatModel` (in each project's test sources) — **no network in CI**.
* `spring.ai.model.chat=none` in test config so provider autoconfiguration stays out of the way.
* Every project must keep these tests green:
  - irreversible tool without a token → refused
  - payload-hash mismatch → refused
  - budget exhaustion → correct fail-closed terminal state
  - `agentic.tools.execution-mode=DRY_RUN` → no writes
* Do not add a test that calls a real model provider to the default build.

## Build

```bash
mvn -q clean verify            # all nine projects, no API key required
cd 0X-<project> && mvn spring-boot:run
```

`ANTHROPIC_API_KEY` is needed only to run an application, never to build or test.

## What not to do

* Do not add a tool that takes raw SQL, a file path, a URL, or a shell command.
* Do not widen an agent's tool list to "make it more capable" without updating the CFG and the
  credential scope.
* Do not replace a deterministic gate with a prompt instruction.
* Do not enable `spring.ai.chat.client.observations.log-prompt` outside local debugging.
* Do not introduce a shared library module — each project stays standalone and runnable on its own,
  which is deliberate duplication for teaching value.
