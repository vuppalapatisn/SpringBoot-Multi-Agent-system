# CLAUDE.md — 01 ChatClient Foundation

Read [`docs/CFG.md`](docs/CFG.md) before changing anything here. Repo-wide rules:
[`../CLAUDE.md`](../CLAUDE.md).

## What this project is for

Teaching the Spring AI 2.x mechanics in a service with an **empty irreversible-action catalogue**.
Its value comes from what it cannot do.

## Entry points

| | |
|---|---|
| HTTP | `web/RefundDeskController` |
| Model call (classify) | `service/RefundClassifier.classify` |
| Model call (draft) | `service/CustomerReplyWriter` |
| Client wiring | `config/ChatClientConfig` |
| Audit | `audit/DecisionLog` |

## Invariants — do not break these

1. **No `@Tool` methods.** If a change needs a tool, it belongs in project 02, not here. Adding one
   here silently moves this project from "zero irreversible actions" to "ungated tool loop".
2. **No writes, no egress.** `CustomerReplyWriter` drafts; it must never gain a send path.
3. **Untrusted customer text goes in the user message only.** Nothing untrusted in
   `defaultSystem`. `RefundClassifierTest.untrustedTextNeverEntersTheSystemMessage` enforces this —
   if it fails, the fix is the code, not the test.
4. **`reconcile()` stays.** The order total overrides the model's proposed amount. Deleting this
   makes the emitted decision unsafe for the downstream projects that consume the same shape.
5. **`advisoryOnly` stays `true`** in the response. It is a contract, not decoration.
6. **Classifier at temperature 0, no memory.** Giving the classifier conversational memory makes
   its judgement path-dependent between runs.

## Gotchas met here

* Bean names collide: an `@Bean ChatClient refundClassifier` method clashes with the
  `@Service RefundClassifier` class. Clients are named `classifierChatClient` / `replyChatClient`.
* Prompt templates use StringTemplate — a literal `{` in a `.st` file breaks rendering. Keep braces
  out of prompt prose.
* Structured-output schema instructions are appended to the user message *after* template
  rendering, so assertions on prompt length must account for them.

## Tests

`mvn -q test` — no network. New behaviour needs a `ScriptedChatModel` case, not a live call.
Never add a test that requires `ANTHROPIC_API_KEY` to the default build.
