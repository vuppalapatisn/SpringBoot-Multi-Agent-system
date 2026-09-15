package io.github.vuppalapatisn.agentic.workflow.domain;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * The model's classification. One LLM node in an otherwise deterministic workflow.
 *
 * <p>The model reads an unstructured complaint against gathered facts and returns a closed
 * vocabulary. It does not decide whether money moves — the policy gate does, from the order record.
 */
@JsonClassDescription("Classification of a customer refund request against the supplied policy clauses")
public record RefundDecision(

        @JsonPropertyDescription("REFUND when a supplied clause clearly allows it, DECLINE when a clause clearly forbids it, ESCALATE when ambiguous or unusual")
        Outcome outcome,

        @JsonPropertyDescription("Identifier of the clause relied on, exactly as supplied, or NONE")
        String clauseId,

        @JsonPropertyDescription("Proposed refund amount in minor units. Must equal the order total for a full refund; use 0 for DECLINE or ESCALATE.")
        long proposedAmountMinor,

        @JsonPropertyDescription("LOW, MEDIUM or HIGH risk of fraud or abuse given the message and the fraud signal")
        Domain.RiskLevel risk,

        @JsonPropertyDescription("One or two sentences for the internal audit record")
        String rationale) {

    public enum Outcome {
        REFUND, DECLINE, ESCALATE
    }

    public static RefundDecision escalate(String reason) {
        return new RefundDecision(Outcome.ESCALATE, "NONE", 0L, Domain.RiskLevel.HIGH, reason);
    }
}
