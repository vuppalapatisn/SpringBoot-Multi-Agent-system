# Tool Boundaries

A tool boundary is the line where **model output becomes an argument to real code**. It is the most
important security and reliability surface in an agentic system, and the one most often left
undefended because the framework makes crossing it a one-liner.

```java
@Tool(description = "Issue a refund for an order")
public RefundReceipt issueRefund(String orderId, long amountMinor) { ... }
```

Eleven characters of annotation just gave a probability distribution the ability to move money.
Classify it before you ship it.

---

## 1. The seven classes

| Class | Name | Reversible | Examples | Mandatory controls |
|-------|------|-----------|----------|--------------------|
| `R0` | read, internal | n/a | own DB, cache, config | rate limit; row-level auth; no free-form query strings |
| `R1` | read, external | n/a | web fetch, partner API, another agent's output, MCP tool result | **treat result as untrusted data**; provenance tag; size cap; timeout; no instruction-following |
| `W1` | write, internal, reversible | automatic | create draft, set status, tag | idempotency key; audit record; undo path implemented |
| `W2` | write, internal, irreversible | **no** | hard delete, ledger post, migration, key rotation | approval gate; checkpoint before; idempotency; dual control if wide |
| `E1` | egress, external, reversible | with cooperation | update draft PR, patch a partner record | approval; allowlist; redaction |
| `E2` | egress, external, irreversible | **no** | payment, email/SMS, publish, provision, order placement | approval gate; checkpoint before; idempotency; rate limit; dry-run; legible confirmation |
| `P1` | privilege change | technically | IAM, secrets, feature flags, quotas | out-of-band approval; never in the agent's own credential scope |

Rules that follow from the table:

1. **One class per tool.** A tool that reads an order *and* pays it is two tools. Mixed-class tools
   cannot be gated correctly because the gate would block the read.
2. **`R1` output is input, not instruction.** A fetched web page, a partner API body, an MCP tool
   result and another agent's message are all the same thing: untrusted text that an attacker may
   control.
3. **`E2`/`W2`/`P1` are never reachable from a single model decision.** There is always a
   deterministic gate in between.
4. **The narrower the tool, the safer the system.** `refundOrder(orderId)` that reads the amount
   from the order is strictly safer than `refund(orderId, amount)` which lets the model pick the
   number. Prefer tools that take *identifiers*, not *values*.

---

## 2. Classification in code, not in a doc

Docs drift. In this repo the class is an annotation the runtime can read
(project [02](../02-tool-calling-guardrails/)):

```java
@Retention(RUNTIME)
@Target(METHOD)
public @interface ToolBoundary {
    BoundaryClass value();
    boolean irreversible() default false;
    String compensation() default "NONE";
    Duration compensationWindow() default ...;
}
```

```java
@Tool(name = "issueRefund", description = "...")
@ToolBoundary(value = E2, irreversible = true,
              compensation = "cancelRefund", compensationWindow = "PT30M")
public RefundReceipt issueRefund(@ToolParam(description = "...") @NotBlank String orderId) { ... }
```

`GuardedToolExecutor` reads the annotation at call time and refuses to execute an `irreversible`
tool without a matching approval token. The consequence that matters: **a new tool added by a
future developer is refused by default** unless they classify it. Fail closed by construction.

A startup check asserts the same invariant, so misclassification is a boot failure rather than a
production surprise:

```java
// ToolBoundaryValidator: every @Tool method must carry @ToolBoundary
// and every irreversible tool must be registered in the approval policy.
```

---

## 3. Argument hygiene

| Risk | Wrong | Right |
|------|-------|-------|
| Injection into a query | `findOrders(String sqlWhere)` | `findOrders(OrderQuery typed)` |
| Path traversal | `readFile(String path)` | `readDocument(DocumentId id)` |
| SSRF | `fetch(String url)` | `fetchFromAllowlist(PartnerId id, String path)` |
| Amount tampering | `refund(orderId, amount)` | `refund(orderId)` — amount read from the order |
| Recipient tampering | `email(to, body)` | `emailCustomer(orderId, templateId, params)` |
| Unbounded fan-out | `refundAll(List<String> orderIds)` | single-order tool + an explicit batch gate |
| Command execution | `runCommand(String cmd)` | do not ship this |

Validate with Bean Validation on the tool parameters and let the framework's
`ToolExecutionExceptionProcessor` turn violations into a message the model can recover from — but
note the direction of trust: the *model* gets a retryable error, while the *system* gets a rejected
call. Never "fix up" a malformed argument on the model's behalf; that is how a `$2.40` refund becomes
`$240.00`.

---

## 4. Idempotency

Every `W*` and `E*` tool takes an idempotency key. Three rules:

1. **The system derives it, never the model.** A model-generated key changes between retries, which
   defeats the entire mechanism.
2. **It is stable across retries and resumes.** Derive it from durable facts:
   `sha256(runId + toolName + businessKey + amountMinor)`.
3. **It is checked at the outermost boundary you control**, and ideally passed to the downstream
   provider as *their* idempotency key too (Stripe-style `Idempotency-Key` header), so a network
   retry you never saw cannot double-charge.

```java
String key = IdempotencyKey.of(run.id(), "issueRefund", order.id(), order.totalMinor());
if (ledger.alreadyApplied(key)) return ledger.priorReceipt(key);   // replay-safe
```

---

## 5. Dry-run and the kill switch

Every `W*`/`E*` tool supports three execution modes:

| Mode | Behaviour | Use |
|------|-----------|-----|
| `EXECUTE` | real effect | production |
| `DRY_RUN` | validates, records intent, returns a synthetic receipt | tests, plan preview, incident response |
| `DISABLED` | refuses with a typed error | kill switch |

One property flips them all — `agentic.tools.execution-mode` — which is the kill switch required by
Phase 10 of the [checklist](00-DESIGN-CHECKLIST.md). It must be changeable **without a deploy**, and
there must be a test that asserts `DRY_RUN` performs no writes.

---

## 6. Framework-level limits (Spring AI 2.x)

Spring AI 2.x provides tool-call limits out of the box. Use them, but understand what they protect:
they bound the *model loop*, not your run.

```yaml
spring:
  ai:
    tools:
      throw-exception-on-error: false        # model gets a recoverable error message
      limits:
        max-total-tool-calls: 12
        max-calls-per-tool-default: 3
        max-calls-per-tool:
          issueRefund: 1                     # belt; the gate is the braces
          notifyCustomer: 1
        on-limit-exceeded: RETURN_ERROR_RESPONSE
        excluded-tools:
          - lookupOrder                      # cheap reads need no ceiling
```

| Layer | Protects against | Does **not** protect against |
|-------|------------------|------------------------------|
| `spring.ai.tools.limits.*` | runaway model loops, repeated calls | direct calls to the tool bean; cost outside the loop |
| your `RunBudget` | cost, wall clock, total steps across the run | a single catastrophic call |
| `GuardedToolExecutor` + gate | a single catastrophic call | nothing — this is the last line |

All three. They are not alternatives.

---

## 7. MCP-specific boundaries

When tools arrive over MCP, the boundary moves but does not disappear — see project
[05](../05-mcp-client-agent/).

| Risk | Control |
|------|---------|
| **Tool poisoning** — a server's tool *description* carries injected instructions | treat descriptions as untrusted; pin and review them; never render them into the system message |
| **Rug pull** — tool list or schema changes after approval | hash the tool list at startup; `McpToolFilter` allowlist; alert on change (`McpToolsChangedEvent`) |
| **Name collision** — two servers expose `issueRefund` | prefix tool names per server (`McpToolNamePrefixGenerator`); reject unprefixed duplicates |
| **Confused deputy** — remote server acts with your credentials | one credential scope per server; server-side policy too |
| **Over-broad server** | allowlist the tools you use, deny by default |
| **Unbounded result size** | cap result bytes before they enter the context |

Server side (project [04](../04-mcp-server-tools/)) carries its own duty: a server must not rely on
the client to enforce policy. It re-checks authority, applies its own rate limits, and returns a
structured "approval required" result rather than performing an irreversible action on request.

---

## 8. Review checklist

- [ ] Every `@Tool` / `@McpTool` method carries a boundary class in code.
- [ ] Startup fails if a tool is unclassified or an irreversible tool is unregistered.
- [ ] No tool spans two classes.
- [ ] Tools take identifiers, not values, wherever a value can be looked up.
- [ ] All arguments typed + validated; no raw SQL/path/URL/command parameters.
- [ ] Every `W*`/`E*` tool: system-derived idempotency key, dry-run, rate limit, audit record.
- [ ] Irreversible tools unreachable without an approval token.
- [ ] `R1` results: size-capped, provenance-tagged, never treated as instructions.
- [ ] MCP: per-server allowlist, name prefixes, tool-list hash pinned, change alerts on.
- [ ] Framework limits configured **and** an independent run budget enforced.
- [ ] Kill switch tested.

---

**Next:** [04 — Approvals & Irreversibility](04-APPROVALS-AND-IRREVERSIBILITY.md)
