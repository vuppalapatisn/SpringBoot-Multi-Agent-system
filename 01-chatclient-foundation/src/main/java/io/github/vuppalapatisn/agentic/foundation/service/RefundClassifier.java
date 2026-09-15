package io.github.vuppalapatisn.agentic.foundation.service;

import io.github.vuppalapatisn.agentic.foundation.advisor.RunContextAdvisor;
import io.github.vuppalapatisn.agentic.foundation.config.FoundationProperties;
import io.github.vuppalapatisn.agentic.foundation.domain.OrderSummary;
import io.github.vuppalapatisn.agentic.foundation.domain.RefundDecision;
import io.github.vuppalapatisn.agentic.foundation.domain.RefundOutcome;
import io.github.vuppalapatisn.agentic.foundation.domain.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Turns an unstructured customer message into a typed {@link RefundDecision}.
 *
 * <p>This is the shape to copy: the model reads messy text and emits a closed-vocabulary record.
 * It does not call tools, it does not decide whether money moves, and its numeric output is treated
 * as a <b>proposal</b> that trusted data overrides.
 */
@Service
public class RefundClassifier {

    private static final Logger log = LoggerFactory.getLogger(RefundClassifier.class);
    private static final Resource USER_PROMPT = new ClassPathResource("prompts/classify-refund.st");

    private final ChatClient classifierChatClient;
    private final FoundationProperties properties;

    RefundClassifier(ChatClient classifierChatClient, FoundationProperties properties) {
        this.classifierChatClient = classifierChatClient;
        this.properties = properties;
    }

    /**
     * @param runId          correlation id for the whole run — on every log line and audit record
     * @param order          trusted order facts
     * @param customerMessage untrusted free text from the customer
     */
    public RefundDecision classify(String runId, OrderSummary order, String customerMessage) {
        String bounded = boundInput(customerMessage);
        try {
            RefundDecision raw = classifierChatClient.prompt()
                    .advisors(a -> a
                            .param(RunContextAdvisor.RUN_ID, runId)
                            .param(RunContextAdvisor.STEP, "classify-refund"))
                    .user(u -> u.text(USER_PROMPT)
                            .param("orderId", order.orderId())
                            .param("orderTotal", money(order.totalMinor(), order.currency()))
                            .param("orderTotalMinor", order.totalMinor())
                            .param("orderStatus", order.status())
                            .param("orderAgeDays", order.ageInDays(LocalDate.now()))
                            .param("itemDescription", order.itemDescription())
                            // Untrusted text, fenced and labelled as data. Never in the system message.
                            .param("customerMessage", bounded))
                    .call()
                    .entity(RefundDecision.class);

            return reconcile(runId, raw, order);
        }
        catch (RuntimeException ex) {
            // Phase 8: unparseable output, provider error and refusal all land here and take a
            // defined path. They must never surface as a 500 that a caller retries blindly.
            log.warn("classification failed for run {} order {}: {}", runId, order.orderId(), ex.toString());
            return RefundDecision.escalate("Classification unavailable: " + ex.getClass().getSimpleName());
        }
    }

    /**
     * Reconciles model output against trusted data.
     *
     * <p>An amount that does not match the order is an <b>integrity signal</b>, not a negotiation:
     * the order total wins and the run escalates to a human. This is the code that makes prompt
     * injection into the amount pointless — there is no path by which model text becomes the number
     * that gets paid.
     */
    private RefundDecision reconcile(String runId, RefundDecision decision, OrderSummary order) {
        if (decision == null || decision.outcome() == null || decision.risk() == null) {
            return RefundDecision.escalate("Model returned an incomplete decision");
        }
        if (decision.outcome() != RefundOutcome.REFUND) {
            return decision;
        }
        if (decision.proposedAmountMinor() != order.totalMinor()) {
            log.warn("run {}: proposed amount {} != order total {} for {} — escalating",
                    runId, decision.proposedAmountMinor(), order.totalMinor(), order.orderId());
            return new RefundDecision(
                    RefundOutcome.ESCALATE,
                    order.totalMinor(),
                    RiskLevel.HIGH,
                    "AMOUNT_MISMATCH",
                    "Proposed amount did not match the order total; order total applies and a human must confirm.");
        }
        return decision;
    }

    private String boundInput(String text) {
        if (text == null) {
            return "";
        }
        int limit = properties.maxInputChars();
        return text.length() <= limit ? text : text.substring(0, limit) + "\n[truncated]";
    }

    private static String money(long minor, String currency) {
        return "%s %d.%02d".formatted(currency, minor / 100, Math.abs(minor % 100));
    }
}
