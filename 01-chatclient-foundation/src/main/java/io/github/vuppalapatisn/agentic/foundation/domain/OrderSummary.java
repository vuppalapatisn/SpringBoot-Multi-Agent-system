package io.github.vuppalapatisn.agentic.foundation.domain;

import java.time.LocalDate;

/**
 * Trusted facts about an order, read from our own system ({@code R0}).
 *
 * <p>Every value a downstream gate needs to make a decision lives here — total, date, status —
 * so that no decision has to rely on a number the model produced.
 */
public record OrderSummary(
        String orderId,
        String customerId,
        long totalMinor,
        String currency,
        LocalDate placedOn,
        String status,
        String itemDescription) {

    public int ageInDays(LocalDate today) {
        return (int) java.time.temporal.ChronoUnit.DAYS.between(placedOn, today);
    }
}
