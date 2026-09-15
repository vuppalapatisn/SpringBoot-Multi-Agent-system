package io.github.vuppalapatisn.agentic.tools.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Trusted order facts ({@code R0}). The <b>source of truth for the refund amount</b>: the gate and
 * the ledger both read {@code totalMinor} from here, never from model output.
 */
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
