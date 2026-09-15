# CLAUDE.md — 03 RAG with Grounding Gates

Read [`docs/CFG.md`](docs/CFG.md) first. Repo-wide rules: [`../CLAUDE.md`](../CLAUDE.md).

## Entry points

| | |
|---|---|
| HTTP | `web/PolicyController` |
| Pipeline | `service/PolicyAnswerService` |
| **The gate** | `gate/GroundednessGate` ← start here |
| Ingestion | `ingest/PolicyIngestion` |
| Wiring (retriever, augmenter, store, embeddings) | `config/RagConfig` |
| Corpus | `src/main/resources/policies/*.md` |

## Invariants

1. **Citations are verified against retrieval metadata**, never parsed as claims. Do not relax
   `GroundednessGate` to "warn" instead of refuse.
2. **`allowEmptyContext(false)` stays.** Turning it on lets the model answer uncovered questions
   from its own weights.
3. **The tenant filter derives from the authenticated principal.** Never from the question, never
   from model output. Invalid tenants are *rejected*, not escaped.
4. **Retrieved clause bodies are fenced and labelled as reference material** in
   `prompts/rag-augment.st`. Never merge them into the system message.
5. **Startup fails on an empty corpus.** A silently empty index answers everything wrongly.
6. **`NW-INJECTION-CANARY` stays in the corpus.** It is the adversarial fixture for
   `retrievedDocumentInjectionIsContained`. Deleting it removes the test's teeth.

## Corpus format

```
## CLAUSE-ID | tenant | version
clause body…
```

One clause per document. Do not switch to token-count chunking: a chunk that straddles two clauses
is how an answer ends up citing a clause that does not support it.

## Embeddings

`HashingEmbeddingModel` is offline and deterministic **on purpose** — no API key, no ONNX download,
identical retrieval on every machine. It has no semantics, and
`similarityFollowsTermOverlap` asserts that limitation so it stays documented.

To use a real model: add `spring-ai-starter-model-transformers`, delete the `embeddingModel` bean.
Changing the model invalidates the index — that is a migration, not a config tweak.

## Tests

`mvn -q test` — 19 tests, no network.

Retrieval assertions depend on term overlap with the corpus. If you edit `policies/*.md`, re-check
`PolicyIngestionTest.indexedClausesAreRetrievable` and `RagPipelineTest.groundedAnswer` — and fix
the corpus or the query, not the threshold.

`RagPipelineTest` shares one `ScriptedChatModel` across tests via a static field, so each test must
enqueue its own answer. Tests are order-independent because every test enqueues before it asks.
