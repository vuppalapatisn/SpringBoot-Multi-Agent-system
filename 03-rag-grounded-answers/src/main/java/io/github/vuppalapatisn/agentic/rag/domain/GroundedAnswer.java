package io.github.vuppalapatisn.agentic.rag.domain;

import java.util.List;

/**
 * The result of a grounded question. Note that {@code grounded} is decided by a deterministic gate,
 * not by the model's own confidence — a model asked "are you sure?" will say yes.
 *
 * @param answer     the text shown to the caller, or a refusal
 * @param citations  clauses the answer cites, each verified to have actually been retrieved
 * @param retrieved  clause ids that retrieval returned, for debugging and evaluation
 * @param grounded   false when the gate rejected the answer
 * @param gateVerdict why the gate decided what it decided
 */
public record GroundedAnswer(
        String answer,
        List<Citation> citations,
        List<String> retrieved,
        boolean grounded,
        String gateVerdict) {

    /** A verified citation: the clause was retrieved for this question, not invented. */
    public record Citation(String clauseId, String source, String version, String excerpt) {
    }

    public static GroundedAnswer refused(String reason, List<String> retrieved) {
        return new GroundedAnswer(
                "I cannot answer that from the refund policy. A specialist will review it.",
                List.of(), retrieved, false, reason);
    }
}
