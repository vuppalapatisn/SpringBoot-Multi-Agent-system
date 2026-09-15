package io.github.vuppalapatisn.agentic.tools.provider;

import io.github.vuppalapatisn.agentic.tools.domain.RefundReceipt;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in for a payment provider. Money leaves the building here.
 *
 * <p>Two properties that a real integration must also have:
 *
 * <ol>
 *   <li><b>The idempotency key is passed to the provider</b>, not merely checked locally. A local
 *       check cannot protect you from a network retry you never observed; the provider's own
 *       {@code Idempotency-Key} can.</li>
 *   <li><b>The compensation window is modelled</b>. {@link #cancel} works only before settlement,
 *       and after that the effect is genuinely irreversible — which is why the catalogue in
 *       {@code docs/CFG.md} lists a 30-minute window rather than "reversible".</li>
 * </ol>
 */
@Component
public class RefundLedger {

    /** Time after which a refund settles and can no longer be cancelled. */
    public static final Duration SETTLEMENT_DELAY = Duration.ofMinutes(30);

    private final Map<String, RefundReceipt> byIdempotencyKey = new ConcurrentHashMap<>();
    private final Clock clock;

    public RefundLedger(Clock clock) {
        this.clock = clock;
    }

    /**
     * Applies a refund. The provider is given the same idempotency key we recorded, so a duplicate
     * request returns the original receipt rather than paying twice.
     */
    public RefundReceipt issue(String orderId, long amountMinor, String idempotencyKey) {
        return byIdempotencyKey.computeIfAbsent(idempotencyKey, key -> new RefundReceipt(
                "re_" + key.substring(0, 10),
                orderId,
                amountMinor,
                key,
                false,
                false,
                clock.instant()));
    }

    /**
     * Compensating action, valid only inside the window.
     *
     * @throws CompensationWindowClosedException once the refund has settled — a failure here is an
     *                                           incident for a human, never a retry loop
     */
    public RefundReceipt cancel(String idempotencyKey) {
        RefundReceipt receipt = byIdempotencyKey.get(idempotencyKey);
        if (receipt == null) {
            throw new IllegalArgumentException("no refund recorded for key " + idempotencyKey);
        }
        if (settled(receipt)) {
            throw new CompensationWindowClosedException(receipt.receiptId(), receipt.at());
        }
        byIdempotencyKey.remove(idempotencyKey);
        return receipt;
    }

    public boolean settled(RefundReceipt receipt) {
        return clock.instant().isAfter(receipt.at().plus(SETTLEMENT_DELAY));
    }

    public Optional<RefundReceipt> find(String idempotencyKey) {
        return Optional.ofNullable(byIdempotencyKey.get(idempotencyKey));
    }

    public List<RefundReceipt> all() {
        return List.copyOf(byIdempotencyKey.values());
    }

    /** The compensation window has closed; the effect is now irreversible in fact. */
    public static class CompensationWindowClosedException extends RuntimeException {
        public CompensationWindowClosedException(String receiptId, Instant appliedAt) {
            super("refund " + receiptId + " applied at " + appliedAt
                    + " has settled; the 30-minute compensation window is closed");
        }
    }
}
