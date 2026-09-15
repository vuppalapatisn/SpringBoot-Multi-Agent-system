package io.github.vuppalapatisn.agentic.foundation.audit;

import java.time.Duration;
import java.time.Instant;

/**
 * One row of the decision log. Phase 9 of the checklist: you must be able to answer "why did this
 * run decide that?" from stored data, without re-running the model.
 *
 * <p>Note what is stored and what is not. The prompt is stored as a <b>hash</b>; the model's
 * structured output is stored in full because it is the evidence; the customer's free text is not
 * stored here at all.
 */
public record DecisionRecord(
        String runId,
        int seq,
        String step,
        String model,
        String promptHash,
        String structuredOutput,
        Integer promptTokens,
        Integer completionTokens,
        String finishReason,
        Duration took,
        Instant at) {
}
