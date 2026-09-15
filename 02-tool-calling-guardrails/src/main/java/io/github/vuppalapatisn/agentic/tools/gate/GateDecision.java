package io.github.vuppalapatisn.agentic.tools.gate;

/**
 * The output of the policy gate: a decision made by <b>code</b>, with the rule that produced it
 * recorded for the audit trail.
 *
 * @param outcome         what happens next
 * @param rule            the rule identifier, e.g. {@code AUTO_LOW_VALUE_LOW_RISK}
 * @param humanExplanation what an approver will read, rendered by our code and never by the model
 * @param requiredApprovals how many distinct approvers are needed (0 for auto, 2 for dual control)
 */
public record GateDecision(
        GateOutcome outcome,
        String rule,
        String humanExplanation,
        int requiredApprovals) {

    public static GateDecision auto(String rule, String explanation) {
        return new GateDecision(GateOutcome.AUTO, rule, explanation, 0);
    }

    public static GateDecision deny(String rule, String explanation) {
        return new GateDecision(GateOutcome.DENY, rule, explanation, 0);
    }

    public static GateDecision single(String rule, String explanation) {
        return new GateDecision(GateOutcome.NEEDS_APPROVAL, rule, explanation, 1);
    }

    public static GateDecision dual(String rule, String explanation) {
        return new GateDecision(GateOutcome.NEEDS_APPROVAL, rule, explanation, 2);
    }
}
