package io.github.vuppalapatisn.agentic.multiagent.agents;

import io.github.vuppalapatisn.agentic.multiagent.authority.AgentRole;
import io.github.vuppalapatisn.agentic.multiagent.domain.Domain.FraudSignal;
import io.github.vuppalapatisn.agentic.multiagent.domain.Domain.OrderSummary;
import io.github.vuppalapatisn.agentic.multiagent.handoff.Handoffs.RiskFinding;
import io.github.vuppalapatisn.agentic.multiagent.service.OrderDirectory;
import io.github.vuppalapatisn.agentic.multiagent.supervisor.RunLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Produces the risk finding. May read externally; may not write anything
 * ({@link AgentRole#FRAUD}).
 *
 * <p><b>This agent does not call a model at all</b>, and that is the interesting decision. The
 * question "what did the fraud provider say?" has a deterministic answer, so asking a model to
 * paraphrase it would add cost, latency and a nondeterministic step in exchange for nothing.
 *
 * <p>It is still an agent in the architectural sense — a bounded specialist with its own authority
 * and its own handoff contract — and keeping it model-free is the same discipline as
 * {@code docs/02-ARCHITECTURE-COMPARISON.md}'s advice to confine nondeterminism to the smallest
 * node that needs it. A multi-agent system in which every agent must contain an LLM is a system
 * paying for prose it will not read.
 */
@Component
public class FraudAgent {

    private static final Logger log = LoggerFactory.getLogger(FraudAgent.class);

    private final OrderDirectory orders;

    public FraudAgent(OrderDirectory orders) {
        this.orders = orders;
    }

    public RiskFinding assess(RunLedger ledger, OrderSummary order) {
        try {
            FraudSignal signal = orders.fraudSignal(order.orderId());
            return new RiskFinding(signal, signal.risk(), "provider signal mapped to an enum");
        }
        catch (RuntimeException ex) {
            log.warn("fraud lookup failed for {}: {}", order.orderId(), ex.toString());
            // Fail towards caution: UNAVAILABLE is MEDIUM, which cannot satisfy the automatic tier.
            return RiskFinding.unavailable();
        }
    }
}
