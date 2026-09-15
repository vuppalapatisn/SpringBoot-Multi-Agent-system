package io.github.vuppalapatisn.agentic.agentloop.tools;

import io.github.vuppalapatisn.agentic.agentloop.budget.BudgetAdvisor;
import io.github.vuppalapatisn.agentic.agentloop.budget.RunBudget;
import io.github.vuppalapatisn.agentic.agentloop.domain.Domain.FraudSignal;
import io.github.vuppalapatisn.agentic.agentloop.domain.Domain.OrderSummary;
import io.github.vuppalapatisn.agentic.agentloop.gate.GuardedPayout;
import io.github.vuppalapatisn.agentic.agentloop.service.OrderDirectory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

/**
 * The tool surface the loop drives.
 *
 * <p>Every tool consults the run's budget <b>before</b> doing anything, which is what makes the
 * ceilings real: the count is incremented and checked inside the boundary rather than trusted to
 * the framework or to the model's restraint. The framework's own
 * {@code spring.ai.tools.limits.*} settings are configured as well — two layers, as
 * {@code docs/03-TOOL-BOUNDARIES.md} recommends.
 *
 * <p>The tool signature carries the loop-detection input: {@code tool + arguments}. Two identical
 * calls in a row end the run, because a model repeating itself is not going to converge by being
 * allowed another attempt.
 */
@Component
@Validated
public class RefundAgentTools {

    private final OrderDirectory orders;
    private final GuardedPayout guardedPayout;

    public RefundAgentTools(OrderDirectory orders, GuardedPayout guardedPayout) {
        this.orders = orders;
        this.guardedPayout = guardedPayout;
    }

    @Tool(name = "lookupOrder",
            description = "Read the trusted facts about an order: total, currency, status, order date and item.")
    public OrderSummary lookupOrder(
            @ToolParam(description = "Order identifier such as A-1187")
            @NotBlank @Pattern(regexp = "[A-Z]-\\d{4}") String orderId,
            ToolContext toolContext) {
        budget(toolContext).beforeToolCall("lookupOrder", "lookupOrder(" + orderId + ")");
        return orders.require(orderId);
    }

    @Tool(name = "checkFraudSignal",
            description = """
                    Check the external fraud provider for the customer who placed an order. Returns exactly \
                    one of CLEAN, WATCHLIST, VELOCITY_ABUSE, CHARGEBACK_HISTORY, UNAVAILABLE.""")
    public FraudSignal checkFraudSignal(
            @ToolParam(description = "Order identifier such as A-1187")
            @NotBlank @Pattern(regexp = "[A-Z]-\\d{4}") String orderId,
            ToolContext toolContext) {
        budget(toolContext).beforeToolCall("checkFraudSignal", "checkFraudSignal(" + orderId + ")");
        return orders.fraudSignal(orderId);
    }

    @Tool(name = "lookupRefundPolicy",
            description = "Return the refund policy clauses that could apply to an order.")
    public String lookupRefundPolicy(
            @ToolParam(description = "Order identifier such as A-1187")
            @NotBlank @Pattern(regexp = "[A-Z]-\\d{4}") String orderId,
            ToolContext toolContext) {
        budget(toolContext).beforeToolCall("lookupRefundPolicy", "lookupRefundPolicy(" + orderId + ")");
        return """
                RP-30D-NOT-RECEIVED: a delivered order reported as not received may be refunded in full within 30 days.
                RP-30D-DAMAGED: a delivered order reported as damaged or faulty may be refunded in full within 30 days.
                RP-CHANGE-OF-MIND: change of mind is refundable in full within 14 days if unopened.
                RP-IN-TRANSIT: an order that has not been delivered is never refunded.
                RP-ESCALATE-LEGAL: any mention of legal action, a chargeback or a regulator is escalated.""";
    }

    /**
     * The one-way door. No amount parameter exists, and the gate decides — so the worst a wrong
     * model decision achieves here is a suspended approval and an audit row.
     */
    @Tool(name = "issueRefund",
            description = """
                    Attempt to refund an order. The amount is taken from the order record and cannot be \
                    specified. If policy requires a human approval this performs nothing and returns \
                    APPROVAL_REQUIRED with an approval id — report it and stop.""")
    public GuardedPayout.PayoutResult issueRefund(
            @ToolParam(description = "Order identifier such as A-1187")
            @NotBlank @Pattern(regexp = "[A-Z]-\\d{4}") String orderId,
            ToolContext toolContext) {
        RunBudget budget = budget(toolContext);
        budget.beforeToolCall("issueRefund", "issueRefund(" + orderId + ")");
        OrderSummary order = orders.require(orderId);
        GuardedPayout.PayoutResult result = guardedPayout.payout(
                budget.runId(), order, orders.fraudSignal(orderId));
        budget.note("GATE", "issueRefund -> " + result.verdict());
        return result;
    }

    @Tool(name = "notifyCustomer",
            description = """
                    Send the customer a templated notification. Templates: refund-approved, \
                    refund-declined, refund-review. The recipient is taken from the order record.""")
    public String notifyCustomer(
            @ToolParam(description = "Order identifier such as A-1187")
            @NotBlank @Pattern(regexp = "[A-Z]-\\d{4}") String orderId,
            @ToolParam(description = "One of: refund-approved, refund-declined, refund-review")
            @NotBlank String templateId,
            ToolContext toolContext) {
        RunBudget budget = budget(toolContext);
        budget.beforeToolCall("notifyCustomer", "notifyCustomer(" + orderId + "," + templateId + ")");
        OrderSummary order = orders.require(orderId);
        try {
            return guardedPayout.notifyCustomer(orderId, order.customerEmail(), templateId);
        }
        catch (IllegalArgumentException ex) {
            // A recoverable message for the model; the effect did not happen.
            return "REFUSED: " + ex.getMessage();
        }
    }

    /**
     * The budget travels in the tool context, never as a tool parameter — the model must not be
     * able to influence the thing that limits it.
     */
    private static RunBudget budget(ToolContext toolContext) {
        Map<String, Object> context = toolContext == null ? null : toolContext.getContext();
        Object value = context == null ? null : context.get(BudgetAdvisor.RUN_BUDGET);
        if (value instanceof RunBudget budget) {
            return budget;
        }
        throw new IllegalStateException("no RunBudget in the tool context; refusing to run unbudgeted");
    }
}
