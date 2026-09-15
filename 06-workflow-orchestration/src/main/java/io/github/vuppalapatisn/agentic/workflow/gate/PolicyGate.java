package io.github.vuppalapatisn.agentic.workflow.gate;

import io.github.vuppalapatisn.agentic.workflow.config.WorkflowProperties;
import io.github.vuppalapatisn.agentic.workflow.domain.Domain.CaseFacts;
import io.github.vuppalapatisn.agentic.workflow.domain.Domain.RiskLevel;
import io.github.vuppalapatisn.agentic.workflow.domain.RefundDecision;
import org.springframework.stereotype.Component;

/**
 * Stage 4: the gate between the classification and the money.
 *
 * <p>Plain Java, called by the workflow, reading only trusted values: the order record and an
 * enum-valued fraud signal. The model's {@code outcome} is an input to this decision, not the
 * decision itself — and no other input to it came from the model.
 */
@Component
public class PolicyGate {

    public enum Outcome {
        /** A deterministic rule fully authorises it. No human is interrupted. */
        AUTO,
        /** One or two humans must approve the frozen amount. */
        NEEDS_APPROVAL,
        /** Refused by rule. Not an escalation. */
        DENY
    }

    public record Decision(Outcome outcome, String rule, String humanExplanation, int requiredApprovals) {
    }

    private final WorkflowProperties properties;

    public PolicyGate(WorkflowProperties properties) {
        this.properties = properties;
    }

    public Decision decide(CaseFacts facts, RefundDecision classification) {
        long amount = facts.order().totalMinor();
        RiskLevel risk = worstOf(facts.fraudSignal().risk(), classification.risk());

        if (classification.outcome() == RefundDecision.Outcome.DECLINE) {
            return new Decision(Outcome.DENY, "CLASSIFIED_DECLINE",
                    explain(facts, risk, "policy does not allow a refund"), 0);
        }
        if (!"DELIVERED".equals(facts.order().status())) {
            return new Decision(Outcome.DENY, "ORDER_NOT_DELIVERED",
                    explain(facts, risk, "the order has not been delivered"), 0);
        }
        if (classification.outcome() == RefundDecision.Outcome.ESCALATE) {
            // An escalation from the classifier is a request for a human, not a refusal.
            return new Decision(Outcome.NEEDS_APPROVAL, "CLASSIFIER_ESCALATED",
                    explain(facts, risk, "the classifier escalated: " + classification.rationale()), 1);
        }
        if (amount > properties.dualControlAboveMinor() || risk == RiskLevel.HIGH) {
            return new Decision(Outcome.NEEDS_APPROVAL, "DUAL_CONTROL_HIGH_VALUE_OR_RISK",
                    explain(facts, risk, amount > properties.dualControlAboveMinor()
                            ? "above the dual-control threshold" : "flagged high risk"), 2);
        }
        if (amount < properties.autoApproveBelowMinor()
                && risk == RiskLevel.LOW
                && facts.ageInDays() <= properties.maxRefundAgeDays()) {
            return new Decision(Outcome.AUTO, "AUTO_LOW_VALUE_LOW_RISK",
                    explain(facts, risk, "within the automatic tier"), 0);
        }
        return new Decision(Outcome.NEEDS_APPROVAL, "SINGLE_APPROVER_DEFAULT",
                explain(facts, risk, "outside the automatic tier"), 1);
    }

    /** The worst of the two risk views. Caution is the tie-breaker. */
    private static RiskLevel worstOf(RiskLevel first, RiskLevel second) {
        return first.ordinal() >= second.ordinal() ? first : second;
    }

    /** Rendered by our code, from trusted data, for a human to read. */
    private String explain(CaseFacts facts, RiskLevel risk, String because) {
        return """
                Refund %s for order %s (customer %s)
                %s · placed %s (%d days ago) · status %s
                Fraud signal: %s · combined risk: %s
                Gate: %s"""
                .formatted(facts.order().formattedTotal(), facts.order().orderId(),
                        facts.order().customerId(), facts.order().itemDescription(),
                        facts.order().placedOn(), facts.ageInDays(), facts.order().status(),
                        facts.fraudSignal(), risk, because);
    }
}
