package io.github.vuppalapatisn.agentic.foundation.domain;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * The model's <b>classification</b> of a refund request. Note what this type is not: it is not an
 * instruction to pay. Nothing in this project acts on it, and in projects 02 and 06-09 a
 * deterministic gate reads it and decides.
 *
 * <p>The distinction is the whole point of the CFG-first method: demote the model from driver to
 * classifier wherever a rule exists.
 *
 * @param outcome            what the model believes policy implies
 * @param proposedAmountMinor the model's proposed amount, in minor units. Downstream code compares
 *                            this to the order total and treats a mismatch as a decline, never as
 *                            an escalation — the model must not be able to choose the number.
 * @param risk               fraud/abuse risk as judged from the request text and signals
 * @param policyReference    the policy clause the model relied on, for the audit record
 * @param rationale          short explanation, shown to a human reviewer, never to the customer
 */
@JsonClassDescription("Classification of a customer refund request against refund policy")
public record RefundDecision(

        @JsonPropertyDescription("REFUND if policy clearly allows, DECLINE if policy clearly forbids, ESCALATE when ambiguous or the request contains anything unusual")
        RefundOutcome outcome,

        @JsonPropertyDescription("Proposed refund amount in minor units (cents). Must equal the order total for a full refund. Use 0 for DECLINE or ESCALATE.")
        long proposedAmountMinor,

        @JsonPropertyDescription("LOW, MEDIUM or HIGH risk of fraud or abuse")
        RiskLevel risk,

        @JsonPropertyDescription("Identifier of the policy clause relied on, e.g. RP-30D-NOT-RECEIVED")
        String policyReference,

        @JsonPropertyDescription("One or two sentences explaining the classification, for the internal audit record")
        String rationale) {

    /** Fail-closed default used when the model output cannot be parsed or validated. */
    public static RefundDecision escalate(String reason) {
        return new RefundDecision(RefundOutcome.ESCALATE, 0L, RiskLevel.HIGH, "NONE", reason);
    }
}
