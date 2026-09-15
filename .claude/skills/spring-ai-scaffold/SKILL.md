---
name: spring-ai-scaffold
description: Scaffold a new CFG-first Spring Boot 4 + Spring AI 2.x service, or add a tool, gate, budget or orchestration to an existing one, using this repo's verified API patterns. Use when asked to create a new agentic project or module, add a @Tool or @McpTool, wire a ChatClient, set up RAG, expose or consume MCP, or add an approval gate.
---

# Spring AI Scaffold (CFG-first)

Generate code that already satisfies the repo's invariants. **The CFG comes first — if
`docs/CFG.md` does not exist for the target, create it from
`docs/templates/CFG-TEMPLATE.md` before writing Java.**

## Verified stack

| | |
|---|---|
| Java | 21 |
| Spring Boot | 4.0.8 (required baseline for Spring AI 2.x) |
| Spring AI BOM | 2.0.1 |
| Default model | `claude-sonnet-5`; `claude-opus-5` for planner/supervisor roles |

## Spring AI 2.x API — do not copy 1.x snippets

Full trap list with the causing code: `.claude/projects/00-index.md`. The essentials:

```java
// options are BUILDERS, not instances; ChatOptions.Builder is self-typed generic
.defaultOptions(ChatOptions.builder().temperature(0.0d).maxTokens(1024))

// ToolCallbacks.from(bean) → org.springframework.ai.support.ToolCallbacks
// advisors: implement BaseAdvisor (before/after) — CallAdvisor/StreamAdvisor for full control
// RAG: org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor
// QA advisor: org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor
// MCP annotations: org.springframework.ai.mcp.annotation.{McpTool, McpToolParam}
// PromptTemplate.builder().resource(resource)   — no Charset overload
```

**Three traps that silently produce wrong behaviour rather than a compile error:**

1. **Tool calling does nothing** unless the *model's* `getOptions()` returns
   `ToolCallingChatOptions`. `DefaultChatClientUtils` builds request options from
   `chatModel.getOptions().mutate()` and only attaches tool callbacks when that builder is a
   `ToolCallingChatOptions.Builder`; `ToolCallingAdvisor` then skips the loop entirely. Real
   providers satisfy this; **test stubs must override `getOptions()`**.
2. **Advisor order decides what is inside the tool loop.** Only advisors ordered *above*
   `ToolCallingAdvisor.DEFAULT_ORDER` (`Ordered.HIGHEST_PRECEDENCE + 300`) participate in each
   iteration. A step/token budget advisor must be above it; a memory or logging advisor below it.
3. **Budget exhaustion thrown from a tool becomes a message the model ignores**, because a tool
   exception is converted to text by default. List the exception in
   `DefaultToolExecutionExceptionProcessor.rethrowExceptions` to make a ceiling a ceiling.

**Boot 4:** `@AutoConfigureMockMvc` lives in the `spring-boot-webmvc-test` module, package
`org.springframework.boot.webmvc.test.autoconfigure`.

**Bean naming:** name chat clients `…ChatClient`. An `@Bean ChatClient refundClassifier()` collides
with an `@Service RefundClassifier`.

Artifact names:

| Purpose | Artifact |
|---------|----------|
| Anthropic model | `spring-ai-starter-model-anthropic` |
| Chat memory | `spring-ai-starter-model-chat-memory` |
| RAG modules | `spring-ai-rag` |
| QA / memory vector advisors | `spring-ai-vector-store-advisor` |
| MCP server (WebMVC) | `spring-ai-starter-mcp-server-webmvc` |
| MCP client | `spring-ai-starter-mcp-client` |
| MCP annotations | `spring-ai-mcp-annotations` |

## New project layout

```
NN-<name>/
├── pom.xml                  # parent spring-boot-starter-parent 4.0.8 + spring-ai-bom 2.0.1
├── README.md                # purpose, CFG summary, endpoints, how to run
├── CLAUDE.md                # agent notes: entry points, invariants, what not to touch
├── docs/CFG.md              # FIRST — from docs/templates/CFG-TEMPLATE.md
└── src/
    ├── main/java/io/github/vuppalapatisn/agentic/<pkg>/
    │   ├── <Name>Application.java
    │   ├── web/         # @RestController — the [IN] node, @Valid records
    │   ├── domain/      # records, enums, sealed results — no framework imports
    │   ├── tools/       # @Tool methods + @ToolBoundary
    │   ├── gate/        # PolicyGate, ApprovalStore, GuardedToolExecutor
    │   ├── budget/      # RunBudget
    │   ├── audit/       # DecisionLog
    │   └── config/      # @ConfigurationProperties records, ChatClient beans
    ├── main/resources/
    │   ├── application.yml
    │   └── prompts/*.st
    └── test/java/...    # ScriptedChatModel-based tests, no network
```

## Mandatory patterns

### A tool

```java
@Tool(name = "issueRefund", description = "Issue a refund for an order. Amount is read from the order.")
@ToolBoundary(value = BoundaryClass.E2, irreversible = true,
              compensation = "cancelRefund", compensationWindow = "PT30M")
RefundReceipt issueRefund(@ToolParam(description = "Order id, e.g. A-1187") @NotBlank String orderId) {
    // amount is looked up, never taken from the model
}
```

Rules: one boundary class per tool; take identifiers not values; validate every argument; every
`W*`/`E*` tool needs a system-derived idempotency key and a dry-run mode.

### A gate (never an advisor)

Advisors wrap the *model call*. Approval gates must wrap the *effect*, inside the tool boundary, so
there is no bypass via a direct bean call. Put the check in `GuardedToolExecutor`.

### A budget

Numeric limits for steps, tool calls, tokens, cost, wall clock. Exhaustion **fails closed** to an
escalation terminal state. Add a test that drives each budget to exhaustion.

### A ChatClient bean

```java
@Bean
ChatClient refundClassifier(ChatClient.Builder builder, ChatMemory memory) {
    return builder
        .defaultSystem(systemPromptResource)                     // static, trusted text only
        .defaultOptions(AnthropicChatOptions.builder().temperature(0.0))
        .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build(),
                         SimpleLoggerAdvisor.builder().build())
        .build();
}
```

Untrusted content goes in the **user** message, labelled as data. Never in `defaultSystem`.

### Tests

`ScriptedChatModel implements ChatModel` returning queued responses; `spring.ai.model.chat=none` in
test properties. Required cases: no-token refusal, hash mismatch, budget exhaustion, `DRY_RUN`
performs no writes.

## Procedure

1. Confirm/author `docs/CFG.md` — graph, tool classes, irreversible catalogue, gates, budgets.
2. Report the agency budget and the four invariants back to the user before generating code.
3. Generate `pom.xml`, then `domain` → `tools` → `gate` → `budget` → `orchestration` → `web`.
4. Generate tests for the four mandatory cases plus the happy path.
5. Run `mvn -q verify` and fix real compile/test errors. Do not weaken an invariant to make a test
   pass.
6. Write `README.md` and `CLAUDE.md`; add the project to the aggregator `pom.xml` and the root
   README table.
