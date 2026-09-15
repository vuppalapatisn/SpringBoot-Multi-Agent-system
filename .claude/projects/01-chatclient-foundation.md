# Brief — 01 ChatClient Foundation

**Purpose:** learn the Spring AI 2.x mechanics where the irreversible-action catalogue is empty.

**Read first:** `01-chatclient-foundation/docs/CFG.md`, then `CLAUDE.md` in the same directory.

**Start in:** `service/RefundClassifier` — the model classifies, `reconcile()` decides.

**Do not break**
- No `@Tool` methods here. A tool belongs in project 02.
- `CustomerReplyWriter` drafts and must never gain a send path.
- Untrusted text goes in the user message only; `untrustedTextNeverEntersTheSystemMessage` proves it.
- `reconcile()` stays: the order total overrides the model's proposed amount.

**Tests:** 10, `mvn -q test`, no network.
