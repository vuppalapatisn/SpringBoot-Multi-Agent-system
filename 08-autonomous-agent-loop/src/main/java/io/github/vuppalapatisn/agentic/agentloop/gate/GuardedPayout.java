package io.github.vuppalapatisn.agentic.agentloop.gate;

import io.github.vuppalapatisn.agentic.agentloop.config.AgentProperties;
import io.github.vuppalapatisn.agentic.agentloop.domain.Domain.FraudSignal;
import io.github.vuppalapatisn.agentic.agentloop.domain.Domain.OrderSummary;
import io.github.vuppalapatisn.agentic.agentloop.domain.Domain.RiskLevel;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The gate and the effects, together, <b>outside the model's reach</b>.
 *
 * <p>In an agent architecture the model decides what to do next, so the question is not whether it
 * will eventually propose something wrong — it will — but what happens when it does. Everything in
 * this class runs regardless of what the model asked for:
 *
 * <ul>
 *   <li>the amount comes from the order record, so the tool needs no amount parameter;</li>
 *   <li>the tiered policy decides, from trusted values only;</li>
 *   <li>an irreversible payout above the automatic tier is <b>suspended</b>, not performed;</li>
 *   <li>the idempotency key is derived from the run, so a retrying agent cannot pay twice;</li>
 *   <li>{@code dry-run} refuses every effect.</li>
 * </ul>
 *
 * <p>Deliberately <b>not</b> an advisor. An advisor wraps the model call; this wraps the effect,
 * and a direct call to the tool bean must not be able to skip it.
 */
@Component
public class GuardedPayout {

    private static final Logger log = LoggerFactory.getLogger(GuardedPayout.class);

    public enum Verdict {
        APPLIED, REPLAYED, APPROVAL_REQUIRED, DECLINED, REFUSED
    }

    public record PayoutResult(Verdict verdict, String message, String receiptId, String approvalId,
                               long amountMinor) {
    }

    public record PendingApproval(String id, String runId, String orderId, long amountMinor,
                                  String rule, String explanation, int requiredApprovals,
                                  List<String> approvers, boolean executed, Instant createdAt) {
    }

    private final Map<String, String> receiptsByKey = new ConcurrentHashMap<>();
    private final Map<String, PendingApproval> approvals = new ConcurrentHashMap<>();
    private final List<String> notifications = new CopyOnWriteArrayList<>();

    private final AgentProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    public GuardedPayout(AgentProperties properties, MeterRegistry meters, Clock clock) {
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * @param runId  the run's id — the model never supplies it, so idempotency keys are stable
     * @param order  trusted order facts; the amount is read from here
     * @param signal the enum-valued fraud signal
     */
    public PayoutResult payout(String runId, OrderSummary order, FraudSignal signal) {
        if (properties.dryRun()) {
            return new PayoutResult(Verdict.REFUSED,
                    "Dry run: nothing was paid.", null, null, order.totalMinor());
        }
        if (!"DELIVERED".equals(order.status())) {
            meters.counter("agentic.agent.gate", "rule", "ORDER_NOT_DELIVERED", "outcome", "DENY").increment();
            return new PayoutResult(Verdict.DECLINED,
                    "Order %s is %s; an undelivered order is never refunded."
                            .formatted(order.orderId(), order.status()), null, null, order.totalMinor());
        }

        String key = idempotencyKey(runId, order);
        String existing = receiptsByKey.get(key);
        if (existing != null) {
            return new PayoutResult(Verdict.REPLAYED,
                    "This refund was already issued in this run.", existing, null, order.totalMinor());
        }

        RiskLevel risk = signal.risk();
        boolean dual = order.totalMinor() > properties.dualControlAboveMinor() || risk == RiskLevel.HIGH;
        boolean automatic = !dual
                && order.totalMinor() < properties.autoApproveBelowMinor()
                && risk == RiskLevel.LOW
                && order.ageInDays(LocalDate.now(clock)) <= properties.maxRefundAgeDays();

        if (automatic) {
            String receiptId = "re_" + sha256(key).substring(0, 10);
            receiptsByKey.put(key, receiptId);
            meters.counter("agentic.agent.gate", "rule", "AUTO_LOW_VALUE_LOW_RISK", "outcome", "AUTO")
                    .increment();
            return new PayoutResult(Verdict.APPLIED,
                    "Refunded %s for order %s under the automatic tier."
                            .formatted(order.formattedTotal(), order.orderId()),
                    receiptId, null, order.totalMinor());
        }

        String rule = dual ? "DUAL_CONTROL_HIGH_VALUE_OR_RISK" : "SINGLE_APPROVER_DEFAULT";
        PendingApproval approval = new PendingApproval(
                "ap-" + UUID.randomUUID().toString().substring(0, 8),
                runId, order.orderId(), order.totalMinor(), rule,
                explain(order, signal, rule), dual ? 2 : 1, List.of(), false, clock.instant());
        approvals.put(approval.id(), approval);
        meters.counter("agentic.agent.gate", "rule", rule, "outcome", "NEEDS_APPROVAL").increment();
        log.info("run {}: payout suspended for human approval {} ({})", runId, approval.id(), rule);

        return new PayoutResult(Verdict.APPROVAL_REQUIRED,
                ("A human approval is required by rule %s. Approval %s is pending and nothing has "
                        + "been paid. Report this and stop.").formatted(rule, approval.id()),
                null, approval.id(), order.totalMinor());
    }

    /** Templated notification to the address on the order. The model supplies neither. */
    public String notifyCustomer(String orderId, String email, String templateId) {
        if (!List.of("refund-approved", "refund-declined", "refund-review").contains(templateId)) {
            throw new IllegalArgumentException("unknown template '" + templateId + "'");
        }
        if (properties.notificationAllowlist().stream()
                .noneMatch(suffix -> email != null && email.toLowerCase().endsWith(suffix.toLowerCase()))) {
            throw new IllegalArgumentException("recipient is not on the notification allowlist: " + email);
        }
        if (properties.dryRun()) {
            return "Dry run: nothing was sent.";
        }
        String body = switch (templateId) {
            case "refund-approved" -> "Your refund for order %s has been approved.".formatted(orderId);
            case "refund-declined" -> "We are unable to offer a refund for order %s.".formatted(orderId);
            default -> "A specialist is reviewing your request for order %s.".formatted(orderId);
        };
        notifications.add(body);
        return body;
    }

    public Optional<PendingApproval> approval(String approvalId) {
        return Optional.ofNullable(approvals.get(approvalId));
    }

    public List<PendingApproval> pendingApprovals() {
        return approvals.values().stream().filter(approval -> !approval.executed()).toList();
    }

    public int paymentCount() {
        return receiptsByKey.size();
    }

    public List<String> notifications() {
        return List.copyOf(notifications);
    }

    /** Tests share one context; clear the simulated world between them. */
    public void reset() {
        receiptsByKey.clear();
        approvals.clear();
        notifications.clear();
    }

    private String explain(OrderSummary order, FraudSignal signal, String rule) {
        return """
                Refund %s for order %s (customer %s)
                %s · placed %s · status %s
                Fraud signal: %s (risk %s)
                Rule: %s"""
                .formatted(order.formattedTotal(), order.orderId(), order.customerId(),
                        order.itemDescription(), order.placedOn(), order.status(),
                        signal, signal.risk(), rule);
    }

    private static String idempotencyKey(String runId, OrderSummary order) {
        return sha256(String.join("|", runId, "issueRefund", order.orderId(),
                Long.toString(order.totalMinor())));
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
