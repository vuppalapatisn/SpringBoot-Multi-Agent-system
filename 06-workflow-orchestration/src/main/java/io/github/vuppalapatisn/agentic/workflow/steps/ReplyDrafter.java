package io.github.vuppalapatisn.agentic.workflow.steps;

import io.github.vuppalapatisn.agentic.workflow.domain.Domain.OrderSummary;
import io.github.vuppalapatisn.agentic.workflow.domain.RefundDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 6: the <b>evaluator–optimiser</b> pattern, bounded.
 *
 * <p>Draft → critique → revise, at most {@link #MAX_ROUNDS} rounds, and only while the critic
 * actually objects. Three properties make this a workflow pattern rather than an agent:
 *
 * <ul>
 *   <li>the loop bound is a constant in code, not a model decision;</li>
 *   <li>the critic returns a machine-checkable verdict ({@code OK} or {@code REVISE: …}), so the
 *       exit condition is a string comparison, not a judgement;</li>
 *   <li>if the loop exhausts, the <b>last draft is still checked</b> by a deterministic rule and
 *       replaced with a safe template if it fails — the run never emits an unvetted message.</li>
 * </ul>
 *
 * <p>Unbounded self-critique is a cost leak dressed as quality. Two rounds captures most of the
 * benefit; the third mostly rewords.
 */
@Component
public class ReplyDrafter {

    private static final Logger log = LoggerFactory.getLogger(ReplyDrafter.class);
    static final int MAX_ROUNDS = 2;

    private static final String SAFE_FALLBACK =
            "Thank you for contacting us about your order. We have reviewed your request and a "
                    + "specialist will confirm the outcome by email.";

    /** Phrases a customer-facing refund message must never contain. */
    private static final List<String> FORBIDDEN = List.of(
            "guarantee", "guaranteed", "immediately", "policy exception", "risk score", "fraud");

    private final ChatClient drafterChatClient;

    public ReplyDrafter(ChatClient drafterChatClient) {
        this.drafterChatClient = drafterChatClient;
    }

    public record DraftResult(String reply, int rounds, List<String> critiques, boolean usedFallback) {
    }

    public DraftResult draft(OrderSummary order, RefundDecision decision) {
        List<String> critiques = new ArrayList<>();
        String draft = generate(order, decision, null);

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            String critique = critique(draft);
            if (critique.startsWith("OK")) {
                return finish(draft, round, critiques);
            }
            critiques.add(critique);
            draft = generate(order, decision, critique);
        }

        log.info("reply drafting used all {} rounds for order {}", MAX_ROUNDS, order.orderId());
        return finish(draft, MAX_ROUNDS, critiques);
    }

    /**
     * The deterministic check that runs regardless of what the critic said. An LLM critic measures
     * tone; a rule decides whether a forbidden promise reached a customer.
     */
    private DraftResult finish(String draft, int rounds, List<String> critiques) {
        String lower = draft == null ? "" : draft.toLowerCase();
        boolean unsafe = draft == null || draft.isBlank()
                || FORBIDDEN.stream().anyMatch(lower::contains);
        if (unsafe) {
            log.warn("draft rejected by the deterministic check; using the safe template");
            return new DraftResult(SAFE_FALLBACK, rounds, critiques, true);
        }
        return new DraftResult(draft.trim(), rounds, critiques, false);
    }

    private String generate(OrderSummary order, RefundDecision decision, String critique) {
        return drafterChatClient.prompt()
                .user(user -> user.text("""
                                Draft a customer reply.

                                - order id: {orderId}
                                - item: {item}
                                - outcome: {outcome}
                                - previous critique to address, if any: {critique}

                                Four sentences maximum. Never state an amount, a date, or a promise of payment.
                                Never mention internal policy codes or risk assessments. Reply text only.""")
                        .param("orderId", order.orderId())
                        .param("item", order.itemDescription())
                        .param("outcome", decision.outcome().name())
                        .param("critique", critique == null ? "none" : critique))
                .call()
                .content();
    }

    private String critique(String draft) {
        String verdict = drafterChatClient.prompt()
                .user(user -> user.text("""
                                Review this customer reply draft.

                                Reply with exactly "OK" if it is acceptable, or "REVISE: <reason>" if it
                                states an amount, a payment date, a promise of payment, an internal policy code,
                                an internal risk assessment, or exceeds four sentences.

                                --- BEGIN DRAFT ---
                                {draft}
                                --- END DRAFT ---""")
                        .param("draft", draft == null ? "" : draft))
                .call()
                .content();
        return verdict == null ? "OK" : verdict.trim();
    }
}
