package io.github.vuppalapatisn.agentic.multiagent.agents;

import io.github.vuppalapatisn.agentic.multiagent.authority.AgentRole;
import io.github.vuppalapatisn.agentic.multiagent.domain.Domain.OrderSummary;
import io.github.vuppalapatisn.agentic.multiagent.gate.GuardedPayout;
import io.github.vuppalapatisn.agentic.multiagent.handoff.Handoffs.PayoutDecision;
import io.github.vuppalapatisn.agentic.multiagent.handoff.Handoffs.PolicyFinding;
import io.github.vuppalapatisn.agentic.multiagent.handoff.Handoffs.RiskFinding;
import io.github.vuppalapatisn.agentic.multiagent.supervisor.RunLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The only agent that can change the world ({@link AgentRole#PAYOUT}).
 *
 * <p>Note what it does <b>not</b> do: it does not call a model to decide whether to pay. The inputs
 * are two typed findings and a trusted order record, and the decision is a rule — so the decision
 * is code, and the gate is code, and the model is nowhere near the money.
 *
 * <p>That may look like a cheat in a "multi-agent" project. It is the honest version. The value of
 * this architecture is separation of authority, not putting a language model in front of a payment
 * API; and the agent that holds the dangerous capability is exactly the one that should have the
 * smallest prompt surface. Here that surface is <b>zero</b> — no {@code ChatClient} is wired into
 * this class at all, so there is nothing for an injected instruction to reach.
 *
 * <p>Customer notifications are fixed templates for the same reason. If generated prose is ever
 * needed on this path, generate it in a component with no capability and hand the text over; do not
 * give the payout agent a model.
 */
@Component
public class PayoutAgent {

    private static final Logger log = LoggerFactory.getLogger(PayoutAgent.class);

    private final GuardedPayout guardedPayout;

    public PayoutAgent(GuardedPayout guardedPayout) {
        this.guardedPayout = guardedPayout;
    }

    /**
     * @param ledger the run ledger; the runId comes from the supervisor, never from an agent
     * @param policy the policy agent's verdict — an input to the rule, not the rule
     * @param risk   the fraud agent's enum-valued finding
     */
    public PayoutDecision settle(RunLedger ledger, OrderSummary order,
                                 PolicyFinding policy, RiskFinding risk) {
        if (policy.verdict() == PolicyFinding.Verdict.NOT_REFUNDABLE) {
            return new PayoutDecision("DECLINED",
                    "Policy clause %s does not allow a refund.".formatted(policy.clauseId()),
                    null, null, order.totalMinor());
        }
        if (policy.verdict() == PolicyFinding.Verdict.UNCLEAR) {
            // Unclear is not a licence to pay. It is a licence to ask a human.
            return new PayoutDecision("APPROVAL_REQUIRED",
                    "The policy position is unclear (%s); a specialist must decide. Nothing was paid."
                            .formatted(policy.reason()),
                    null, null, order.totalMinor());
        }
        PayoutDecision decision = guardedPayout.payout(ledger.runId(), order, risk.signal());
        log.info("run {}: payout agent -> {}", ledger.runId(), decision.verdict());
        return decision;
    }

    /** Sends the templated notification. Recipient and template are not the model's to choose. */
    public String notifyCustomer(OrderSummary order, String templateId) {
        try {
            return guardedPayout.notifyCustomer(order.orderId(), order.customerEmail(), templateId);
        }
        catch (IllegalArgumentException ex) {
            log.warn("notification refused: {}", ex.getMessage());
            return "REFUSED: " + ex.getMessage();
        }
    }
}
