package io.github.vuppalapatisn.agentic.agentloop.domain;

import io.github.vuppalapatisn.agentic.agentloop.budget.Budget;
import io.github.vuppalapatisn.agentic.agentloop.budget.RunBudget;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Shared domain shapes. Same vocabulary as projects 06, 07 and 09. */
public final class Domain {

    private Domain() {
    }

    public record OrderSummary(
            String orderId,
            String customerId,
            String customerEmail,
            long totalMinor,
            String currency,
            LocalDate placedOn,
            String status,
            String itemDescription) {

        public int ageInDays(LocalDate today) {
            return (int) ChronoUnit.DAYS.between(placedOn, today);
        }

        public String formattedTotal() {
            return "%s %d.%02d".formatted(currency, totalMinor / 100, Math.abs(totalMinor % 100));
        }
    }

    public enum RiskLevel {
        LOW, MEDIUM, HIGH
    }

    public enum FraudSignal {

        CLEAN(RiskLevel.LOW),
        WATCHLIST(RiskLevel.MEDIUM),
        VELOCITY_ABUSE(RiskLevel.HIGH),
        CHARGEBACK_HISTORY(RiskLevel.HIGH),
        UNAVAILABLE(RiskLevel.MEDIUM);

        private final RiskLevel risk;

        FraudSignal(RiskLevel risk) {
            this.risk = risk;
        }

        public RiskLevel risk() {
            return risk;
        }
    }

    /** How a run ended. Note that there is no "ran out of budget so paid anyway". */
    public enum RunOutcome {
        /** The agent finished and reported. */
        COMPLETED,
        /** A refund was applied. */
        REFUNDED,
        /** A rule refused it. */
        DECLINED,
        /** A human must decide: either a gate said so, or a budget ran out. */
        ESCALATED,
        /** Something broke that was not a budget. */
        FAILED
    }

    /**
     * The result of an agent run — the answer plus the receipt for how it was reached.
     *
     * @param exhaustedBudget which budget ran out, when one did. Null on a normal finish.
     */
    public record AgentRunResult(
            String runId,
            RunOutcome outcome,
            String reply,
            String receiptId,
            String approvalId,
            Budget exhaustedBudget,
            int modelTurns,
            int toolCalls,
            int totalTokens,
            long estimatedCostMinor,
            Duration elapsed,
            List<RunBudget.Step> trace) {

        public static AgentRunResult from(RunBudget budget, RunOutcome outcome, String reply,
                                          String receiptId, String approvalId, Budget exhausted) {
            return new AgentRunResult(budget.runId(), outcome, reply, receiptId, approvalId, exhausted,
                    budget.modelTurns(), budget.toolCalls(), budget.totalTokens(),
                    budget.estimatedCostMinor(), budget.elapsed(), budget.steps());
        }
    }
}
