package io.github.vuppalapatisn.agentic.statemachine.gate;

import io.github.vuppalapatisn.agentic.statemachine.config.StateMachineProperties;
import io.github.vuppalapatisn.agentic.statemachine.domain.Domain.CaseFacts;
import io.github.vuppalapatisn.agentic.statemachine.domain.Domain.RiskLevel;
import io.github.vuppalapatisn.agentic.statemachine.domain.RefundDecision;
import org.springframework.stereotype.Component;

/** The tiered gate. Same rules as project 06; the difference is what happens to the decision after. */
@Component
public class PolicyGate {

    public enum Outcome {
        AUTO, NEEDS_APPROVAL, DENY
    }

    public record Decision(Outcome outcome, String rule, String humanExplanation, int requiredApprovals) {
    }

    private final StateMachineProperties properties;

    public PolicyGate(StateMachineProperties properties) {
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

    private static RiskLevel worstOf(RiskLevel first, RiskLevel second) {
        return first.ordinal() >= second.ordinal() ? first : second;
    }

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
