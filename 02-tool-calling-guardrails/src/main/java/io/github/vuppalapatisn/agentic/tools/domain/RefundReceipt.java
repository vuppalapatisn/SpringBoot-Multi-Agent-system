package io.github.vuppalapatisn.agentic.tools.domain;

import java.time.Instant;

/**
 * Proof that a refund was applied — or, in dry-run mode, a clearly-marked synthetic stand-in.
 *
 * @param receiptId      provider reference, or {@code dry-run-*}
 * @param orderId        business key
 * @param amountMinor    amount actually applied, always taken from the order
 * @param idempotencyKey the key under which this effect is recorded
 * @param settled        false while the compensation window is still open
 * @param synthetic      true when produced by {@code DRY_RUN}; never treat as money moved
 * @param at             when it happened
 */
public record RefundReceipt(
        String receiptId,
        String orderId,
        long amountMinor,
        String idempotencyKey,
        boolean settled,
        boolean synthetic,
        Instant at) {

    public static RefundReceipt dryRun(String orderId, long amountMinor, String idempotencyKey) {
        return new RefundReceipt("dry-run-" + idempotencyKey.substring(0, 8),
                orderId, amountMinor, idempotencyKey, false, true, Instant.now());
    }
}
