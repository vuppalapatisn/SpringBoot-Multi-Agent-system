package io.github.vuppalapatisn.agentic.multiagent.gate;

import io.github.vuppalapatisn.agentic.multiagent.config.MultiAgentProperties;
import io.github.vuppalapatisn.agentic.multiagent.domain.Domain.FraudSignal;
import io.github.vuppalapatisn.agentic.multiagent.domain.Domain.OrderSummary;
import io.github.vuppalapatisn.agentic.multiagent.domain.Domain.RiskLevel;
import io.github.vuppalapatisn.agentic.multiagent.handoff.Handoffs.PayoutDecision;
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
 * The gate and the two irreversible effects — the capability that <b>only the payout agent holds</b>.
 *
 * <p>Wiring this bean into a second agent would be the single change that destroys this project's
 * security model, which is why {@code AgentCapabilities} refuses to register more than one
 * effectful role and a test asserts it.
 */
@Component
public class GuardedPayout {

    private static final Logger log = LoggerFactory.getLogger(GuardedPayout.class);

    public record PendingApproval(String id, String runId, String orderId, long amountMinor,
                                  String rule, String explanation, int requiredApprovals,
                                  List<String> approvers, boolean executed, Instant createdAt) {
    }

    private final Map<String, String> receiptsByKey = new ConcurrentHashMap<>();
    private final Map<String, PendingApproval> approvals = new ConcurrentHashMap<>();
    private final List<String> notifications = new CopyOnWriteArrayList<>();

    private final MultiAgentProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;

    public GuardedPayout(MultiAgentProperties properties, MeterRegistry meters, Clock clock) {
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * @param runId  supplied by the supervisor, never by an agent
     * @param order  trusted order facts; the amount is read from here
     * @param signal the enum-valued fraud signal
     */
    public PayoutDecision payout(String runId, OrderSummary order, FraudSignal signal) {
        if (properties.dryRun()) {
            return new PayoutDecision("REFUSED", "Dry run: nothing was paid.", null, null,
                    order.totalMinor());
        }
        if (!"DELIVERED".equals(order.status())) {
            meters.counter("agentic.multiagent.gate", "rule", "ORDER_NOT_DELIVERED").increment();
            return new PayoutDecision("DECLINED",
                    "Order %s is %s; an undelivered order is never refunded."
                            .formatted(order.orderId(), order.status()), null, null, order.totalMinor());
        }

        String key = idempotencyKey(runId, order);
        String existing = receiptsByKey.get(key);
        if (existing != null) {
            return new PayoutDecision("REPLAYED", "This refund was already issued in this run.",
                    existing, null, order.totalMinor());
        }

        boolean dual = order.totalMinor() > properties.dualControlAboveMinor()
                || signal.risk() == RiskLevel.HIGH;
        boolean automatic = !dual
                && order.totalMinor() < properties.autoApproveBelowMinor()
                && signal.risk() == RiskLevel.LOW
                && order.ageInDays(LocalDate.now(clock)) <= properties.maxRefundAgeDays();

        if (automatic) {
            String receiptId = "re_" + sha256(key).substring(0, 10);
            receiptsByKey.put(key, receiptId);
            meters.counter("agentic.multiagent.gate", "rule", "AUTO_LOW_VALUE_LOW_RISK").increment();
            return new PayoutDecision("APPLIED",
                    "Refunded %s for order %s under the automatic tier."
                            .formatted(order.formattedTotal(), order.orderId()),
                    receiptId, null, order.totalMinor());
        }

        String rule = dual ? "DUAL_CONTROL_HIGH_VALUE_OR_RISK" : "SINGLE_APPROVER_DEFAULT";
        PendingApproval approval = new PendingApproval(
                "ap-" + UUID.randomUUID().toString().substring(0, 8), runId, order.orderId(),
                order.totalMinor(), rule, explain(order, signal, rule), dual ? 2 : 1, List.of(),
                false, clock.instant());
        approvals.put(approval.id(), approval);
        meters.counter("agentic.multiagent.gate", "rule", rule).increment();
        log.info("run {}: payout suspended for approval {} ({})", runId, approval.id(), rule);

        return new PayoutDecision("APPROVAL_REQUIRED",
                "A human approval is required by rule %s. Approval %s is pending; nothing was paid."
                        .formatted(rule, approval.id()),
                null, approval.id(), order.totalMinor());
    }

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

    public Optional<PendingApproval> approval(String id) {
        return Optional.ofNullable(approvals.get(id));
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
