package io.github.vuppalapatisn.agentic.statemachine.effects;

import io.github.vuppalapatisn.agentic.statemachine.config.StateMachineProperties;
import io.github.vuppalapatisn.agentic.statemachine.support.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stand-in for the payment provider and the notification channel.
 *
 * <p>Three behaviours the state machine depends on:
 *
 * <ul>
 *   <li><b>Idempotency by key</b>, passed to the provider rather than only checked locally;</li>
 *   <li><b>{@link #cancel}</b>, which works only before settlement — the compensation window;</li>
 *   <li><b>{@link #statusFor}</b>, which is how a reconciliation sweeper resolves an
 *       {@code INTENT} entry: it asks the provider "did this key happen?" instead of guessing.</li>
 * </ul>
 *
 * <p>A test hook, {@link #failNextPayout}, lets the suite simulate a crash between recording intent
 * and learning the outcome. That case is the entire justification for the
 * {@code PAYOUT_PENDING} state, so it needs to be exercisable.
 */
@Component
public class RefundProvider {

    private static final Logger log = LoggerFactory.getLogger(RefundProvider.class);

    public record Receipt(String receiptId, long amountMinor, String idempotencyKey,
                          Instant at, Instant settlesAt) {
    }

    public record SentMessage(String to, String body, Instant at) {
    }

    private final Map<String, Receipt> byKey = new ConcurrentHashMap<>();
    private final List<SentMessage> sent = new CopyOnWriteArrayList<>();
    private final StateMachineProperties properties;
    private final Clock clock;

    private volatile boolean failNextPayout;
    private volatile boolean silentlyApplyOnFailure;
    private volatile boolean failNextNotification;

    public RefundProvider(StateMachineProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Receipt pay(String orderId, long amountMinor, String idempotencyKey) {
        Receipt existing = byKey.get(idempotencyKey);
        if (existing != null) {
            return existing;
        }
        if (failNextPayout) {
            failNextPayout = false;
            if (silentlyApplyOnFailure) {
                // The provider applied it but we never learned: the exact case PAYOUT_PENDING and
                // the reconciliation sweeper exist for.
                silentlyApplyOnFailure = false;
                byKey.put(idempotencyKey, receipt(orderId, amountMinor, idempotencyKey));
            }
            throw new ProviderUnavailableException("payment provider timed out");
        }
        if (properties.dryRun()) {
            return receipt(orderId, amountMinor, "dry-run-" + idempotencyKey);
        }
        Receipt receipt = receipt(orderId, amountMinor, idempotencyKey);
        byKey.put(idempotencyKey, receipt);
        return receipt;
    }

    private Receipt receipt(String orderId, long amountMinor, String key) {
        Instant now = clock.instant();
        return new Receipt("re_" + Hashing.sha256(key).substring(0, 10), amountMinor, key, now,
                now.plus(properties.settlementDelay()));
    }

    /** Compensation. Fails once the window has closed — an incident, not a retry. */
    public Receipt cancel(String idempotencyKey) {
        Receipt receipt = byKey.get(idempotencyKey);
        if (receipt == null) {
            throw new IllegalArgumentException("no payment recorded for key " + idempotencyKey);
        }
        if (clock.instant().isAfter(receipt.settlesAt())) {
            throw new CompensationWindowClosedException(receipt.receiptId(), receipt.settlesAt());
        }
        byKey.remove(idempotencyKey);
        log.info("cancelled payment {} inside the compensation window", receipt.receiptId());
        return receipt;
    }

    /** What reconciliation asks the provider. */
    public Optional<Receipt> statusFor(String idempotencyKey) {
        return Optional.ofNullable(byKey.get(idempotencyKey));
    }

    public SentMessage notifyCustomer(String to, String body) {
        if (!allowedRecipient(to)) {
            throw new IllegalArgumentException("recipient is not on the notification allowlist: " + to);
        }
        if (failNextNotification) {
            failNextNotification = false;
            throw new ProviderUnavailableException("notification channel unavailable");
        }
        if (properties.dryRun()) {
            return new SentMessage(to, body, clock.instant());
        }
        SentMessage message = new SentMessage(to, body, clock.instant());
        sent.add(message);
        return message;
    }

    private boolean allowedRecipient(String to) {
        return to != null && properties.notificationAllowlist().stream()
                .anyMatch(suffix -> to.toLowerCase().endsWith(suffix.toLowerCase()));
    }

    public List<SentMessage> sent() {
        return List.copyOf(sent);
    }

    public int paymentCount() {
        return byKey.size();
    }

    // ---- test hooks -------------------------------------------------------

    /**
     * @param alsoApply when true, the provider applies the payment but reports failure — a lost
     *                  acknowledgement rather than a failed payment
     */
    public void failNextPayout(boolean alsoApply) {
        this.failNextPayout = true;
        this.silentlyApplyOnFailure = alsoApply;
    }

    public void failNextNotification() {
        this.failNextNotification = true;
    }

    /**
     * Clears the simulated provider. Tests share one application context, so without this the
     * payment counts of one test leak into the assertions of the next.
     */
    public void reset() {
        byKey.clear();
        sent.clear();
        failNextPayout = false;
        silentlyApplyOnFailure = false;
        failNextNotification = false;
    }

    public static class ProviderUnavailableException extends RuntimeException {
        public ProviderUnavailableException(String message) {
            super(message);
        }
    }

    public static class CompensationWindowClosedException extends RuntimeException {
        public CompensationWindowClosedException(String receiptId, Instant settledAt) {
            super("payment " + receiptId + " settled at " + settledAt
                    + "; the compensation window is closed");
        }
    }
}
