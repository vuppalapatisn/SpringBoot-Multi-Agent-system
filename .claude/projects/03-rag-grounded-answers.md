# Brief — 03 RAG with Grounding Gates

**Purpose:** a retrieval pipeline whose answers are checked, not merely requested politely.

**Read first:** `03-rag-grounded-answers/docs/CFG.md`.

**Start in:** `gate/GroundednessGate` — citations are verified against retrieval metadata.

**Do not break**
- Citations verified by set membership; do not downgrade the gate to a warning.
- `allowEmptyContext(false)` stays.
- The tenant filter derives from the principal, never the question; invalid tenants are rejected.
- `NW-INJECTION-CANARY` stays in the corpus: it is the adversarial fixture.
- Startup fails on an empty corpus.

**Embeddings:** `HashingEmbeddingModel` is offline and deterministic on purpose, and its lack of
semantics is asserted. Swap in `spring-ai-starter-model-transformers` for anything real; changing
the model invalidates the index.

**Tests:** 19.
