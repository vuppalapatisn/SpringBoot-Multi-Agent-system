package io.github.vuppalapatisn.agentic.multiagent.domain;

import io.github.vuppalapatisn.agentic.multiagent.handoff.Handoffs;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Shared domain shapes. Same vocabulary as projects 06, 07 and 08. */
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

    public enum RunOutcome {
        REFUNDED, DECLINED, ESCALATED, FAILED
    }

    /**
     * The result of a supervised run.
     *
     * @param blackboard everything the specialists contributed — the audit of who knew what
     * @param handoffs   the sequence of agents, in order
     * @param terminatedBy why the run stopped, when it stopped early
     */
    public record SupervisedRunResult(
            String runId,
            RunOutcome outcome,
            String reply,
            String receiptId,
            String approvalId,
            Handoffs.Blackboard blackboard,
            List<String> handoffs,
            String terminatedBy,
            int modelCalls,
            Duration elapsed) {
    }
}
