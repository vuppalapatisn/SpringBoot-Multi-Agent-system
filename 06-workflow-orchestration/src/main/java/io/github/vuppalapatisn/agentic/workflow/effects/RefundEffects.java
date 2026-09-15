package io.github.vuppalapatisn.agentic.workflow.effects;

import io.github.vuppalapatisn.agentic.workflow.config.WorkflowProperties;
import io.github.vuppalapatisn.agentic.workflow.domain.Domain.OrderSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The two irreversible effects, behind one component so the workflow has exactly one door to the
 * outside world.
 *
 * <p>Both take an <b>identifier</b> and read values from trusted records: there is no parameter
 * through which a caller — or a model — could choose an amount or a recipient. Both are keyed by a
 * system-derived idempotency key, and both respect the dry-run kill switch.
 *
 * <p>Ordering matters and is enforced by the workflow, not by convention: the payout runs first and
 * the notification second, because the notification is the <b>less</b> reversible of the two. If
 * the payout fails, nothing was said to the customer.
 */
@Component
public class RefundEffects {

    private static final Logger log = LoggerFactory.getLogger(RefundEffects.class);

    public record Receipt(String receiptId, long amountMinor, String idempotencyKey,
                          boolean synthetic, Instant at) {
    }

    public record SentMessage(String to, String body, boolean synthetic, Instant at) {
    }

    private final Map<String, Receipt> receiptsByKey = new ConcurrentHashMap<>();
    private final List<SentMessage> sent = new CopyOnWriteArrayList<>();
    private final WorkflowProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    public RefundEffects(WorkflowProperties properties, MeterRegistry meters, Clock clock) {
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    /** {@code E2} irreversible. Compensation window is out of scope here; project 07 has the saga. */
    public Receipt issueRefund(String runId, OrderSummary order) {
        String key = idempotencyKey(runId, "issueRefund", order.orderId(), order.totalMinor());
        Receipt existing = receiptsByKey.get(key);
        if (existing != null) {
            meters.counter("agentic.workflow.effect", "effect", "issueRefund", "outcome", "REPLAYED")
                    .increment();
            return existing;
        }
        if (properties.dryRun()) {
            meters.counter("agentic.workflow.effect", "effect", "issueRefund", "outcome", "DRY_RUN")
                    .increment();
            return new Receipt("dry-run-" + key.substring(0, 8), order.totalMinor(), key, true,
                    clock.instant());
        }
        Receipt receipt = new Receipt("re_" + key.substring(0, 10), order.totalMinor(), key, false,
                clock.instant());
        receiptsByKey.put(key, receipt);
        meters.counter("agentic.workflow.effect", "effect", "issueRefund", "outcome", "APPLIED")
                .increment();
        return receipt;
    }

    /** {@code E2} irreversible, compensation NONE. Recipient comes from the order record. */
    public SentMessage notifyCustomer(String runId, OrderSummary order, String body) {
        if (!allowedRecipient(order.customerEmail())) {
            throw new IllegalArgumentException(
                    "recipient is not on the notification allowlist: " + order.customerEmail());
        }
        if (properties.dryRun()) {
            meters.counter("agentic.workflow.effect", "effect", "notifyCustomer", "outcome", "DRY_RUN")
                    .increment();
            return new SentMessage(order.customerEmail(), body, true, clock.instant());
        }
        SentMessage message = new SentMessage(order.customerEmail(), body, false, clock.instant());
        sent.add(message);
        meters.counter("agentic.workflow.effect", "effect", "notifyCustomer", "outcome", "APPLIED")
                .increment();
        log.info("run {}: notified {}", runId, order.customerEmail());
        return message;
    }

    public Optional<Receipt> receiptFor(String runId, OrderSummary order) {
        return Optional.ofNullable(receiptsByKey.get(
                idempotencyKey(runId, "issueRefund", order.orderId(), order.totalMinor())));
    }

    public List<SentMessage> sent() {
        return List.copyOf(sent);
    }

    public int paymentCount() {
        return receiptsByKey.size();
    }

    private boolean allowedRecipient(String email) {
        return email != null && properties.notificationAllowlist().stream()
                .anyMatch(suffix -> email.toLowerCase().endsWith(suffix.toLowerCase()));
    }

    /** Derived by the system from durable facts. Never from model output, never random. */
    static String idempotencyKey(String runId, String effect, String businessKey, long amountMinor) {
        return sha256(String.join("|", runId, effect, businessKey, Long.toString(amountMinor)));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
