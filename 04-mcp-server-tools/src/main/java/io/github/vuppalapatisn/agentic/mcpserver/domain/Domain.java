package io.github.vuppalapatisn.agentic.mcpserver.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The server's domain types, gathered in one file because they are small and always read together.
 *
 * <p>Everything an MCP client receives is one of these shapes. None of them is free text produced
 * by a model, and none of them carries a field the client could use to steer a later decision —
 * a tool result is data, and this is the server's half of that contract.
 */
public final class Domain {

    private Domain() {
    }

    /** Trusted order facts. The authority for the refund amount. */
    public record OrderSummary(
            String orderId,
            String customerId,
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

    /** The closed vocabulary an external fraud provider's answer is mapped onto. */
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

    /** What the server decided, and what a client may do about it. */
    public enum ServerOutcome {
        /** The refund was applied. */
        APPLIED,
        /** Already applied under the same idempotency key; the original receipt is returned. */
        REPLAYED,
        /**
         * The server did nothing and requires a human approval, which the client cannot grant.
         * This is the interesting case: the answer to "please pay" is a receipt for a decision,
         * not a payment.
         */
        APPROVAL_REQUIRED,
        /** A server-side rule forbids it. Not an escalation. */
        DECLINED,
        /** Refused by a server-side control: rate limit, kill switch, or a bad argument. */
        REFUSED
    }

    /**
     * The result of an attempt to issue a refund.
     *
     * @param outcome     what happened, from the closed set above
     * @param explanation why, rendered by the server from trusted data
     * @param approvalId  the pending approval, when one was created
     * @param receiptId   the provider reference, when money actually moved
     * @param amountMinor the authoritative amount, always from the order record
     * @param at          when the server decided
     */
    public record RefundOutcome(
            ServerOutcome outcome,
            String explanation,
            String approvalId,
            String receiptId,
            long amountMinor,
            Instant at) {
    }
}
