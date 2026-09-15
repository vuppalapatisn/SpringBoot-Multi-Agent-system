# 01 — ChatClient Foundation

**What it teaches:** the Spring AI 2.x mechanics — `ChatClient`, prompt templates, structured
output, chat memory, advisors, observability, resilience — with the CFG discipline applied from the
first line, in a service where **nothing can go wrong**.

**Why this project has no tools:** learn the framework where the irreversible-action catalogue is
empty. Every later project adds exactly one kind of risk.

> Control-flow graph: [`docs/CFG.md`](docs/CFG.md) · Agency budget: **0** · Irreversible actions:
> **none**

---

## The job

```
POST /api/refunds/classify
{ "orderId": "A-1187", "message": "The parcel never arrived." }
```

→ classify the request against refund policy, draft a customer reply, and return both. **No money
moves. No email is sent.** The response carries `advisoryOnly: true` as a contract.

---

## The one idea to take away

> **Demote the model from driver to classifier wherever a rule exists.**

The model reads messy human text — which is what it is good at — and emits a closed-vocabulary
record:

```java
RefundDecision decision = classifierChatClient.prompt()
        .user(u -> u.text(USER_PROMPT).param("customerMessage", bounded))
        .call()
        .entity(RefundDecision.class);      // RefundOutcome enum + amount + risk + policy ref
```

Then **code** decides. In particular, [`RefundClassifier.reconcile`](src/main/java/io/github/vuppalapatisn/agentic/foundation/service/RefundClassifier.java)
compares the model's proposed amount against the order total and, on mismatch, escalates with the
order total applying:

```java
if (decision.proposedAmountMinor() != order.totalMinor()) {
    return new RefundDecision(ESCALATE, order.totalMinor(), HIGH, "AMOUNT_MISMATCH", ...);
}
```

That single method is why prompt injection into the *amount* is pointless here. There is no path by
which model text becomes the number that gets paid.

---

## What is in the code

| Concern | File | Note |
|---------|------|------|
| Ingress validation | [`web/ClassifyRequest`](src/main/java/io/github/vuppalapatisn/agentic/foundation/web/ClassifyRequest.java) | `@Pattern` on the order id, `@Size` on the message — the first trust boundary |
| Two distinct clients | [`config/ChatClientConfig`](src/main/java/io/github/vuppalapatisn/agentic/foundation/config/ChatClientConfig.java) | classifier at temp 0 with no memory; reply writer warmer with windowed memory |
| Structured output | [`domain/RefundDecision`](src/main/java/io/github/vuppalapatisn/agentic/foundation/domain/RefundDecision.java) | record + enums + `@JsonPropertyDescription` so the schema teaches the model |
| Prompt templates | [`resources/prompts/`](src/main/resources/prompts/) | never inline strings; untrusted text fenced in the **user** message |
| Custom advisor | [`advisor/RunContextAdvisor`](src/main/java/io/github/vuppalapatisn/agentic/foundation/advisor/RunContextAdvisor.java) | `runId` → MDC, usage → decision log |
| Audit | [`audit/DecisionLog`](src/main/java/io/github/vuppalapatisn/agentic/foundation/audit/DecisionLog.java) | append-only, one row per model call |
| Typed config | [`config/FoundationProperties`](src/main/java/io/github/vuppalapatisn/agentic/foundation/config/FoundationProperties.java) | validated — a misconfiguration is a boot failure |

### Why the advisor is an advisor — and the gate (later) is not

`RunContextAdvisor` observes the **model call**, which is exactly what advisors wrap. An approval
gate must wrap the **effect**, so in project 02 it lives inside the tool boundary instead. An
advisor-based gate is bypassed by any code that calls the tool bean directly.

---

## Run it

```bash
export ANTHROPIC_API_KEY=sk-ant-...
mvn spring-boot:run
```

```bash
curl -s localhost:8080/api/refunds/classify -H 'Content-Type: application/json' \
  -d '{"orderId":"A-1187","message":"The parcel never arrived and I want my money back."}'
```

```bash
curl -N -s localhost:8080/api/refunds/draft/stream -H 'Content-Type: application/json' \
  -d '{"orderId":"A-1204","message":"Cable stopped working after two days."}'
```

```bash
curl -s localhost:8080/api/refunds/runs/r-1a2b3c4d/decisions
```

Seeded orders: `A-1187` (\$240, delivered, 9 days), `A-1204` (\$89.90, delivered, 3 days),
`A-0988` (\$1,899, delivered, 58 days — out of window), `A-1310` (\$45, in transit).

### Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/refunds/classify` | classify + draft (advisory only) |
| `POST` | `/api/refunds/draft/stream` | SSE stream of the draft reply |
| `GET` | `/api/refunds/runs/{runId}/decisions` | the decision log for a run |
| `GET` | `/actuator/metrics`, `/actuator/prometheus` | observations |

---

## Tests

```bash
mvn -q test          # no API key, no network
```

| Test | Asserts |
|------|---------|
| `acceptsMatchingAmount` | a well-formed classification passes through |
| `rejectsInflatedAmount` | an inflated amount escalates; the order total wins |
| `failsClosedOnUnparseableOutput` | prose instead of JSON → `ESCALATE`, not a 500 |
| `failsClosedOnProviderError` | provider down → `ESCALATE` |
| `untrustedTextNeverEntersTheSystemMessage` | **the prompt trust boundary, asserted** |
| `boundsInputSize` | oversize input is clipped before reaching the context |
| `recordsTheDecision` | usage, model, finish reason and sequence land in the audit log |
| `RefundDeskApiTest.*` | full context wiring; ingress rejection; 404 without a model call |

All tests run against [`ScriptedChatModel`](src/test/java/io/github/vuppalapatisn/agentic/foundation/testsupport/ScriptedChatModel.java).
Copy that class — it is the single most useful piece of test infrastructure for agentic services.

---

## Spring AI 2.x notes (differs from 1.x)

```java
// options are BUILDERS, not instances
.defaultOptions(ChatOptions.builder().model("claude-sonnet-5").temperature(0.0d).maxTokens(1024))

// ChatOptions.Builder is self-typed generic: ChatOptions.Builder<B extends Builder<B>>
// advisors implement BaseAdvisor with before()/after(); getOrder() is required
// memory: MessageWindowChatMemory.builder().chatMemoryRepository(repo).maxMessages(n).build()
// MessageChatMemoryAdvisor.builder(chatMemory).build()
```

Boot 4 gotcha met while writing this project: `@AutoConfigureMockMvc` moved to the new
`spring-boot-webmvc-test` module, package `org.springframework.boot.webmvc.test.autoconfigure`.

---

## What this project deliberately does not do

| Missing | Where it appears |
|---------|------------------|
| Tools the model can call | [02](../02-tool-calling-guardrails/) |
| Approval gates, idempotency, dry-run | [02](../02-tool-calling-guardrails/) |
| Retrieval over policy documents | [03](../03-rag-grounded-answers/) |
| Remote tools over MCP | [04](../04-mcp-server-tools/), [05](../05-mcp-client-agent/) |
| Orchestration across steps | [06](../06-workflow-orchestration/)–[09](../09-multi-agent-supervisor/) |

**Next:** [02 — Tool Calling with Guardrails](../02-tool-calling-guardrails/)
