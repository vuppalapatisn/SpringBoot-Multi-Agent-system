package io.github.vuppalapatisn.agentic.multiagent.supervisor;

import io.github.vuppalapatisn.agentic.multiagent.agents.FraudAgent;
import io.github.vuppalapatisn.agentic.multiagent.agents.IntakeAgent;
import io.github.vuppalapatisn.agentic.multiagent.agents.PayoutAgent;
import io.github.vuppalapatisn.agentic.multiagent.agents.PolicyAgent;
import io.github.vuppalapatisn.agentic.multiagent.authority.AgentRole;
import io.github.vuppalapatisn.agentic.multiagent.config.MultiAgentProperties;
import io.github.vuppalapatisn.agentic.multiagent.domain.Domain.OrderSummary;
import io.github.vuppalapatisn.agentic.multiagent.domain.Domain.RunOutcome;
import io.github.vuppalapatisn.agentic.multiagent.domain.Domain.SupervisedRunResult;
import io.github.vuppalapatisn.agentic.multiagent.handoff.Handoffs.Blackboard;
import io.github.vuppalapatisn.agentic.multiagent.handoff.Handoffs.IntakeSummary;
import io.github.vuppalapatisn.agentic.multiagent.handoff.Handoffs.PayoutDecision;
import io.github.vuppalapatisn.agentic.multiagent.handoff.Handoffs.PolicyFinding;
import io.github.vuppalapatisn.agentic.multiagent.handoff.Handoffs.RiskFinding;
import io.github.vuppalapatisn.agentic.multiagent.service.OrderDirectory;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * The supervisor — <b>deliberately code, not a model</b>.
 *
 * <pre>
 *   intake ──▶ policy ──▶ fraud ──▶ payout ──▶ notify
 *      │          │         │
 *      └──────────┴─────────┴──▶ escalate (any flag, or any budget)
 * </pre>
 *
 * <p>{@code docs/02-ARCHITECTURE-COMPARISON.md} lists "supervisor as code, specialists as agents"
 * as a hybrid, and recommends it for a specific reason: routing is the highest-variance decision in
 * a multi-agent system and the one with the least to gain from a model. A {@code switch} costs
 * nothing, never loops, never hallucinates a specialist that does not exist, and is testable.
 *
 * <p>What remains genuinely multi-agent is the part that matters: four specialists with different
 * authority, typed handoffs between them, and per-agent budgets. Only {@link PayoutAgent} can move
 * money.
 *
 * <p>Escalation policy, applied before anything irreversible: the intake agent's flags
 * ({@code mentionsEscalation}, {@code containsInstructionsToTheAssistant}, {@code UNCLEAR}) each
 * force a human, whatever the rest of the pipeline concluded. Noticing manipulation is something a
 * model does reliably; resisting it is not, so the flag is turned into a rule.
 */
@Service
public class RefundSupervisor {

    private static final Logger log = LoggerFactory.getLogger(RefundSupervisor.class);

    private final IntakeAgent intakeAgent;
    private final PolicyAgent policyAgent;
    private final FraudAgent fraudAgent;
    private final PayoutAgent payoutAgent;
    private final OrderDirectory orders;
    private final MultiAgentProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    public RefundSupervisor(IntakeAgent intakeAgent,
                            PolicyAgent policyAgent,
                            FraudAgent fraudAgent,
                            PayoutAgent payoutAgent,
                            OrderDirectory orders,
                            MultiAgentProperties properties,
                            MeterRegistry meters,
                            Clock clock) {
        this.intakeAgent = intakeAgent;
        this.policyAgent = policyAgent;
        this.fraudAgent = fraudAgent;
        this.payoutAgent = payoutAgent;
        this.orders = orders;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    public Optional<SupervisedRunResult> handle(String orderId, String customerMessage) {
        OrderSummary order = orders.find(orderId).orElse(null);
        if (order == null) {
            return Optional.empty();
        }

        String runId = "r-" + UUID.randomUUID().toString().substring(0, 8);
        RunLedger ledger = new RunLedger(runId, properties, clock);
        Blackboard board = Blackboard.start(runId, orderId);

        try {
            // ---- intake: the only agent that sees attacker-controlled text ----------------
            ledger.handoffTo(AgentRole.INTAKE);
            IntakeSummary intake = intakeAgent.read(ledger, customerMessage);
            board = board.with(intake).handedTo(AgentRole.INTAKE.name());

            if (intake.requiresHuman()) {
                // A flag from intake outranks everything downstream, and stops before any effect.
                meters.counter("agentic.multiagent.escalation", "reason", "INTAKE_FLAG").increment();
                return Optional.of(escalate(ledger, board, order,
                        intake.containsInstructionsToTheAssistant()
                                ? "The request contains instructions addressed to an automated system "
                                  + "and has been referred to a specialist."
                                : "This request has been referred to a specialist.",
                        "INTAKE_FLAG"));
            }

            // ---- policy: reads only, and never sees the raw message ----------------------
            ledger.handoffTo(AgentRole.POLICY);
            PolicyFinding policy = policyAgent.assess(ledger, order, intake);
            board = board.with(policy).handedTo(AgentRole.POLICY.name());

            // ---- fraud: external read, enum out -----------------------------------------
            ledger.handoffTo(AgentRole.FRAUD);
            RiskFinding risk = fraudAgent.assess(ledger, order);
            board = board.with(risk).handedTo(AgentRole.FRAUD.name());

            // ---- payout: the only agent with a capability that changes anything ---------
            ledger.handoffTo(AgentRole.PAYOUT);
            PayoutDecision payout = payoutAgent.settle(ledger, order, policy, risk);
            board = board.with(payout).handedTo(AgentRole.PAYOUT.name());

            return Optional.of(finish(ledger, board, order, payout));
        }
        catch (RunLedger.TerminationException ex) {
            // FAIL CLOSED. A budget or a termination rule escalates; it never proceeds.
            log.warn("run {} terminated by {}: {}", runId, ex.rule(), ex.getMessage());
            meters.counter("agentic.multiagent.terminated", "rule", ex.rule()).increment();
            return Optional.of(new SupervisedRunResult(runId, RunOutcome.ESCALATED,
                    "This request could not be completed within its limits (%s) and has been "
                            .formatted(ex.rule()) + "escalated to a specialist. Nothing was paid.",
                    null, null, board, ledger.handoffNames(), ex.rule(),
                    ledger.totalModelCalls(), ledger.elapsed()));
        }
        catch (RuntimeException ex) {
            log.error("run {} failed: {}", runId, ex.toString());
            meters.counter("agentic.multiagent.run", "outcome", RunOutcome.FAILED.name()).increment();
            return Optional.of(new SupervisedRunResult(runId, RunOutcome.FAILED,
                    "This request could not be completed automatically and has been logged for review.",
                    null, null, board, ledger.handoffNames(), "EXCEPTION",
                    ledger.totalModelCalls(), ledger.elapsed()));
        }
    }

    private SupervisedRunResult escalate(RunLedger ledger, Blackboard board, OrderSummary order,
                                         String reply, String reason) {
        // Escalation is itself an egress, so it goes through the payout agent's gated notification.
        payoutAgent.notifyCustomer(order, "refund-review");
        return new SupervisedRunResult(ledger.runId(), RunOutcome.ESCALATED, reply, null, null,
                board, ledger.handoffNames(), reason, ledger.totalModelCalls(), ledger.elapsed());
    }

    private SupervisedRunResult finish(RunLedger ledger, Blackboard board, OrderSummary order,
                                       PayoutDecision payout) {
        RunOutcome outcome = switch (payout.verdict()) {
            case "APPLIED", "REPLAYED" -> RunOutcome.REFUNDED;
            case "DECLINED" -> RunOutcome.DECLINED;
            default -> RunOutcome.ESCALATED;
        };
        String template = switch (outcome) {
            case REFUNDED -> "refund-approved";
            case DECLINED -> "refund-declined";
            default -> "refund-review";
        };
        payoutAgent.notifyCustomer(order, template);
        meters.counter("agentic.multiagent.run", "outcome", outcome.name()).increment();

        return new SupervisedRunResult(ledger.runId(), outcome, payout.message(),
                payout.receiptId(), payout.approvalId(), board, ledger.handoffNames(), null,
                ledger.totalModelCalls(), ledger.elapsed());
    }
}
