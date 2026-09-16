# Project index and cross-project knowledge

Nine standalone Maven projects over one domain (Refund Desk). Each has its own `CLAUDE.md` and
`docs/CFG.md`; the files in this directory are the short briefs — what to read first, what not to
break, where the tests are.

| # | Project | Brief | Teaches | Irreversible actions | Tests |
|---|---------|-------|---------|----------------------|-------|
| 01 | `01-chatclient-foundation` | [brief](01-chatclient-foundation.md) | ChatClient, structured output, memory, advisors | none | 14 |
| 02 | `02-tool-calling-guardrails` | [brief](02-tool-calling-guardrails.md) | tool boundaries, gates, approvals, idempotency | 2 | 38 |
| 03 | `03-rag-grounded-answers` | [brief](03-rag-grounded-answers.md) | RAG + groundedness gate + tenant isolation | none | 19 |
| 04 | `04-mcp-server-tools` | [brief](04-mcp-server-tools.md) | MCP server, server-side policy, separation of duty | 1 | 17 |
| 05 | `05-mcp-client-agent` | [brief](05-mcp-client-agent.md) | MCP client trust boundary, pinning, tool poisoning | delegated | 15 |
| 06 | `06-workflow-orchestration` | [brief](06-workflow-orchestration.md) | deterministic DAG, four workflow patterns | 2 | 17 |
| 07 | `07-state-machine-orchestration` | [brief](07-state-machine-orchestration.md) | persisted FSM, durable approvals, saga | 2 | 18 |
| 08 | `08-autonomous-agent-loop` | [brief](08-autonomous-agent-loop.md) | budgets, loop detection, fail-closed exhaustion | 2 | 18 |
| 09 | `09-multi-agent-supervisor` | [brief](09-multi-agent-supervisor.md) | separation of authority, typed handoffs | 2 | 26 |

Projects **06–09 solve the identical problem**. When comparing architectures, diff them.
Projects **04 + 05** are a pair: run the server, then point the client at it.

182 tests in total, none of which touch the network.

For how the nine fit together — system context, the layering they share, dataflow with trust
boundaries, and the deployment topology — see [`docs/ARCHITECTURE.md`](../../docs/ARCHITECTURE.md).
It is the structural counterpart to the per-project `docs/CFG.md`.

---

## Spring AI 2.0.1 gotchas — the single source of truth

Every one of these was hit while building this repo. They are the things that waste an afternoon.

### Tool calling silently does nothing

Two conditions, both invisible at compile time:

```java
// DefaultChatClientUtils: the request's options come from the MODEL, not from defaultOptions
ChatOptions.Builder<?> builder = inputRequest.getChatModel().getOptions().mutate();
if (builder instanceof ToolCallingChatOptions.Builder<?> tb) { /* attach tool callbacks */ }

// ToolCallingAdvisor.adviseCall: skips the whole loop otherwise
if (!(options instanceof ToolCallingChatOptions)) return chain.nextCall(request);
```

⇒ **A test stub `ChatModel` must override `getOptions()`** to return `ToolCallingChatOptions`.
Real providers (`AnthropicChatOptions`) already do.

### Advisor order decides what is inside the tool loop

`ToolCallingAdvisor.DEFAULT_ORDER` is `HIGHEST_PRECEDENCE + 300`, and **only advisors with a higher
order participate in each tool-call iteration**.

* Counting model turns or tokens per iteration → order **above** 300 (project 08 uses +400).
* Wrapping the whole call once (memory, logging) → order **below** 300.

Getting this wrong in project 08 made `maxSteps` unreachable and hung the test suite.

### A thrown budget exception becomes a message the model ignores

By default a tool exception is converted to text and handed back to the model. Right for a
validation error, catastrophic for a budget:

```java
DefaultToolExecutionExceptionProcessor.builder()
        .alwaysThrow(false)
        .rethrowExceptions(List.of(BudgetExceededException.class))
        .build();
```

### Swapping the model provider

Verified at 2.0.1. `spring.ai.model.chat` selects the active auto-configuration; values are the
constants in `org.springframework.ai.model.SpringAIModels`:

| | Anthropic | Google Gemini |
|---|---|---|
| starter | `spring-ai-starter-model-anthropic` | `spring-ai-starter-model-google-genai` |
| selector | `anthropic` | `google-genai` |
| key | `spring.ai.anthropic.api-key` | `spring.ai.google.genai.api-key` |
| token ceiling | `max-tokens` | **`max-output-tokens`** |

Both starters are on the classpath in this repo, so the selector **must** be set — otherwise two
`ChatModel` beans exist and the `ChatClient` cannot be built. `application.yml` sets it from
`${AI_CHAT_PROVIDER:anthropic}`.

**Only the active provider's properties are bound**, so only its key is required: an inactive
provider's `${…_API_KEY}` placeholder is never resolved. `GeminiProviderTest` in project 01 proves
this by booting the context on Gemini with no Anthropic key present.

`GoogleGenAiChatOptions implements ToolCallingChatOptions, StructuredOutputChatOptions` — the same
pair as `AnthropicChatOptions` — which is why tool calling and `.entity()` work on either. A model
whose options lack `ToolCallingChatOptions` silently does no tool calling at all (see above).

Note: Spring AI ships `GoogleGenAiToolCallingManager` for Gemini's stricter tool JSON schemas and
does **not** wire it by default. Register it if a Gemini tool call fails with a schema complaint.
The old `vertex-ai-gemini` module no longer exists at 2.x; it is `google-genai` now.

### Module renames and relocations

| 1.x / Boot 3 | 2.x / Boot 4 |
|--------------|--------------|
| `spring-ai-advisors-vector-store` | `spring-ai-vector-store-advisor` |
| `@AutoConfigureMockMvc` in `spring-boot-test-autoconfigure` | module **`spring-boot-webmvc-test`**, package `org.springframework.boot.webmvc.test.autoconfigure` |
| — | MCP annotations: `org.springframework.ai.mcp.annotation.@McpTool` / `@McpToolParam` |

### API shapes worth remembering

```java
.defaultOptions(ChatOptions.builder().temperature(0.0d).maxTokens(1024))   // a BUILDER, not built
PromptTemplate.builder().resource(resource)                                // no Charset overload
MessageWindowChatMemory.builder().chatMemoryRepository(repo).maxMessages(n).build()
MessageChatMemoryAdvisor.builder(chatMemory).build()
response.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT)      // retrieved docs
advisor.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, "tenant == 'acme'")
ToolCallbacks.from(bean)                        // org.springframework.ai.support
```

### Bean naming

An `@Bean ChatClient refundClassifier()` collides with an `@Service RefundClassifier` class (both
want the bean name `refundClassifier`). Name chat clients `…ChatClient`.

---

## Testing conventions

* No network in any test. Every project has a `ScriptedChatModel` in its test sources.
* `spring.ai.model.chat=none` plus a stub `ChatModel` bean keeps provider autoconfiguration away.
* Inject `Clock`; use a mutable clock for expiry, settlement and wall-clock budgets.
* **One application context per test class** — reset in-memory providers/gates in `@BeforeEach`, or
  you will chase "expected 1 but was 2".
* Framework limits (`spring.ai.tools.limits.*`) can fire before your own budget. A test that means
  to exercise the application budget should raise the framework's.
