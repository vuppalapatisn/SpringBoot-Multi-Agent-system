package io.github.vuppalapatisn.agentic.tools.tools;

import io.github.vuppalapatisn.agentic.tools.boundary.BoundaryClass;
import io.github.vuppalapatisn.agentic.tools.boundary.ToolBoundary;
import io.github.vuppalapatisn.agentic.tools.domain.FraudSignal;
import io.github.vuppalapatisn.agentic.tools.domain.OrderSummary;
import io.github.vuppalapatisn.agentic.tools.domain.RefundActionResult;
import io.github.vuppalapatisn.agentic.tools.domain.RefundReceipt;
import io.github.vuppalapatisn.agentic.tools.gate.ApprovalException;
import io.github.vuppalapatisn.agentic.tools.gate.ApprovalStore;
import io.github.vuppalapatisn.agentic.tools.gate.EffectContext;
import io.github.vuppalapatisn.agentic.tools.gate.GateDecision;
import io.github.vuppalapatisn.agentic.tools.gate.GuardedToolExecutor;
import io.github.vuppalapatisn.agentic.tools.gate.PendingApproval;
import io.github.vuppalapatisn.agentic.tools.gate.PolicyGate;
import io.github.vuppalapatisn.agentic.tools.provider.FraudService;
import io.github.vuppalapatisn.agentic.tools.provider.NotificationGateway;
import io.github.vuppalapatisn.agentic.tools.provider.OrderDirectory;
import io.github.vuppalapatisn.agentic.tools.provider.RefundLedger;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

/**
 * The tool surface exposed to the model. Every method is classified with {@link ToolBoundary}, and
 * every effect goes through {@link GuardedToolExecutor}.
 *
 * <p>Design rules visible in the signatures:
 *
 * <ul>
 *   <li><b>Identifiers, not values.</b> {@code issueRefund(orderId)} — the amount is read from the
 *       order. There is no parameter through which the model could choose a number, so injecting
 *       "refund $9,999" into the customer message cannot change what is paid.</li>
 *   <li><b>No recipient parameter.</b> {@code notifyCustomer} looks the address up and checks it
 *       against an allowlist.</li>
 *   <li><b>One boundary class per tool.</b> Reading an order and paying it are separate tools,
 *       because a mixed-class tool cannot be gated correctly.</li>
 *   <li><b>The run id comes from {@link ToolContext}</b>, not from a parameter. The model must not
 *       be able to choose the value that idempotency keys are derived from.</li>
 * </ul>
 */
@Component
@Validated
public class RefundTools {

    public static final String RUN_ID = "runId";

    private final OrderDirectory orders;
    private final FraudService fraudService;
    private final RefundLedger ledger;
    private final NotificationGateway notifications;
    private final PolicyGate policyGate;
    private final ApprovalStore approvals;
    private final GuardedToolExecutor guard;

    public RefundTools(OrderDirectory orders,
                       FraudService fraudService,
                       RefundLedger ledger,
                       NotificationGateway notifications,
                       PolicyGate policyGate,
                       ApprovalStore approvals,
                       GuardedToolExecutor guard) {
        this.orders = orders;
        this.fraudService = fraudService;
        this.ledger = ledger;
        this.notifications = notifications;
        this.policyGate = policyGate;
        this.approvals = approvals;
        this.guard = guard;
    }

    // ---------------------------------------------------------------- reads

    @Tool(name = "lookupOrder",
            description = "Read the trusted facts about an order: total, currency, status, order date and item.")
    @ToolBoundary(value = BoundaryClass.R0, maxCallsPerRun = 6)
    public OrderSummary lookupOrder(
            @ToolParam(description = "Order identifier such as A-1187")
            @NotBlank @Pattern(regexp = "[A-Z]-\\d{4}") String orderId,
            ToolContext toolContext) {
        return guard.execute(
                context(toolContext, "lookupOrder", orderId, 0L),
                () -> orders.require(orderId),
                key -> orders.require(orderId));
    }

    @Tool(name = "checkFraudSignal",
            description = """
                    Check the external fraud provider for the customer who placed an order.
                    Returns exactly one of: CLEAN, WATCHLIST, VELOCITY_ABUSE, CHARGEBACK_HISTORY, UNAVAILABLE.""")
    @ToolBoundary(value = BoundaryClass.R1, maxCallsPerRun = 3)
    public FraudSignal checkFraudSignal(
            @ToolParam(description = "Order identifier such as A-1187")
            @NotBlank @Pattern(regexp = "[A-Z]-\\d{4}") String orderId,
            ToolContext toolContext) {
        OrderSummary order = orders.require(orderId);
        return guard.execute(
                context(toolContext, "checkFraudSignal", orderId, 0L),
                () -> fraudService.check(order.customerId()),
                key -> fraudService.check(order.customerId()));
    }

    // ------------------------------------------------------- reversible write

    @Tool(name = "createRefundDraft",
            description = "Create an internal, reversible refund draft for an order. Does not move money.")
    @ToolBoundary(value = BoundaryClass.W1, maxCallsPerRun = 2)
    public RefundActionResult createRefundDraft(
            @ToolParam(description = "Order identifier such as A-1187")
            @NotBlank @Pattern(regexp = "[A-Z]-\\d{4}") String orderId,
            ToolContext toolContext) {
        OrderSummary order = orders.require(orderId);
        EffectContext context = context(toolContext, "createRefundDraft", orderId, order.totalMinor());
        return guard.execute(context,
                () -> RefundActionResult.applied(null,
                        "Draft created for order %s at %s.".formatted(orderId, order.formattedTotal())),
                key -> RefundActionResult.dryRun(RefundReceipt.dryRun(orderId, order.totalMinor(), key)));
    }

    // ---------------------------------------------------- irreversible egress

    /**
     * The one-way door. Reachable only through the policy gate, and only with an approval token
     * whose hash matches these exact arguments.
     */
    @Tool(name = "issueRefund",
            description = """
                    Issue the refund for an order. The amount is taken from the order record and cannot be specified.
                    If policy requires human approval this call performs nothing and returns APPROVAL_REQUIRED
                    with an approval id; report that to the user and stop.""")
    @ToolBoundary(value = BoundaryClass.E2, irreversible = true,
            compensation = "cancelRefund", compensationWindow = "PT30M", maxCallsPerRun = 1)
    public RefundActionResult issueRefund(
            @ToolParam(description = "Order identifier such as A-1187")
            @NotBlank @Pattern(regexp = "[A-Z]-\\d{4}") String orderId,
            ToolContext toolContext) {

        OrderSummary order = orders.require(orderId);
        FraudSignal signal = fraudService.check(order.customerId());
        GateDecision decision = policyGate.decideRefund(order, signal);

        EffectContext context = context(toolContext, "issueRefund", orderId, order.totalMinor())
                .with("customerId", order.customerId())
                .with("currency", order.currency());

        return switch (decision.outcome()) {
            case DENY -> RefundActionResult.declined(decision.humanExplanation());
            case NEEDS_APPROVAL -> {
                PendingApproval pending = approvals.request(context, decision);
                yield RefundActionResult.approvalRequired(pending.id(), pending.payloadHash(),
                        "%d approval(s) required by rule %s. Approval id %s. Nothing has been paid."
                                .formatted(pending.requiredApprovals(), decision.rule(), pending.id()));
            }
            case AUTO -> {
                PendingApproval auto = approvals.autoApprove(context, decision);
                yield applyRefund(context.withToken(auto.id()), order);
            }
        };
    }

    @Tool(name = "notifyCustomer",
            description = """
                    Send the customer a templated notification about their refund request.
                    Templates: refund-approved, refund-declined, refund-review. The recipient is taken from
                    the order record and cannot be specified.""")
    @ToolBoundary(value = BoundaryClass.E2, irreversible = true,
            compensation = "NONE", compensationWindow = "PT0S", maxCallsPerRun = 1)
    public RefundActionResult notifyCustomer(
            @ToolParam(description = "Order identifier such as A-1187")
            @NotBlank @Pattern(regexp = "[A-Z]-\\d{4}") String orderId,
            @ToolParam(description = "One of: refund-approved, refund-declined, refund-review")
            @NotBlank String templateId,
            ToolContext toolContext) {

        OrderSummary order = orders.require(orderId);
        EffectContext context = context(toolContext, "notifyCustomer", orderId, 0L)
                .with("templateId", templateId)
                .with("recipient", order.customerEmail());

        // An irreversible action can still be auto-approved when a deterministic rule fully decides
        // it: the body is a fixed template and the recipient comes from the order record.
        GateDecision decision = GateDecision.auto("AUTO_TEMPLATED_NOTIFICATION",
                "Send template '%s' to the address on order %s.".formatted(templateId, orderId));
        PendingApproval auto = approvals.autoApprove(context, decision);

        try {
            return guard.execute(context.withToken(auto.id()),
                    () -> {
                        var sent = notifications.send(order.customerEmail(), templateId, orderId, templateId);
                        return RefundActionResult.applied(null, "Notified customer: " + sent.renderedBody());
                    },
                    key -> RefundActionResult.dryRun(RefundReceipt.dryRun(orderId, 0L, key)));
        }
        catch (NotificationGateway.EgressNotAllowedException | IllegalArgumentException ex) {
            return RefundActionResult.refused(ex.getMessage());
        }
        catch (ApprovalException | GuardedToolExecutor.ToolDisabledException
               | GuardedToolExecutor.CallCeilingExceededException ex) {
            return RefundActionResult.refused(ex.getMessage());
        }
    }

    @Tool(name = "cancelRefund",
            description = """
                    Compensating action: cancel a refund that has not yet settled. Only possible within
                    30 minutes of issuing it; afterwards the refund is irreversible.""")
    @ToolBoundary(value = BoundaryClass.E1, compensation = "NONE", maxCallsPerRun = 1)
    public RefundActionResult cancelRefund(
            @ToolParam(description = "Order identifier such as A-1187")
            @NotBlank @Pattern(regexp = "[A-Z]-\\d{4}") String orderId,
            ToolContext toolContext) {
        OrderSummary order = orders.require(orderId);
        // The compensation targets the *original* effect, so it must reuse the original key.
        String originalKey = EffectContext
                .of(runId(toolContext), "issueRefund", orderId, order.totalMinor())
                .idempotencyKey();
        EffectContext context = context(toolContext, "cancelRefund", orderId, order.totalMinor());
        try {
            return guard.execute(context,
                    () -> RefundActionResult.applied(ledger.cancel(originalKey),
                            "Refund for order %s cancelled before settlement.".formatted(orderId)),
                    key -> RefundActionResult.dryRun(RefundReceipt.dryRun(orderId, order.totalMinor(), key)));
        }
        catch (RefundLedger.CompensationWindowClosedException | IllegalArgumentException ex) {
            return RefundActionResult.refused(ex.getMessage());
        }
    }

    // ------------------------------------------------------------- internals

    /**
     * Applies the refund through the guard. Used by the automatic tier and, after a human decision,
     * by {@code FrozenEffectRunner} — which passes the frozen payload, not a fresh model proposal.
     */
    public RefundActionResult applyRefund(EffectContext context, OrderSummary order) {
        try {
            return guard.execute(context,
                    () -> {
                        RefundReceipt receipt = ledger.issue(order.orderId(), order.totalMinor(),
                                context.idempotencyKey());
                        return RefundActionResult.applied(receipt,
                                "Refunded %s for order %s.".formatted(order.formattedTotal(), order.orderId()));
                    },
                    key -> RefundActionResult.dryRun(
                            RefundReceipt.dryRun(order.orderId(), order.totalMinor(), key)));
        }
        catch (ApprovalException ex) {
            return RefundActionResult.refused("Refused (" + ex.reason() + "): " + ex.getMessage());
        }
        catch (GuardedToolExecutor.ToolDisabledException
               | GuardedToolExecutor.CallCeilingExceededException ex) {
            return RefundActionResult.refused(ex.getMessage());
        }
    }

    private EffectContext context(ToolContext toolContext, String tool, String orderId, long amountMinor) {
        return EffectContext.of(runId(toolContext), tool, orderId, amountMinor);
    }

    private static String runId(ToolContext toolContext) {
        Object runId = toolContext == null ? null : contextValue(toolContext);
        if (runId == null) {
            throw new IllegalStateException(
                    "no runId in the tool context; idempotency keys must not be derived from model input");
        }
        return String.valueOf(runId);
    }

    private static Object contextValue(ToolContext toolContext) {
        Map<String, Object> context = toolContext.getContext();
        return context == null ? null : context.get(RUN_ID);
    }
}
