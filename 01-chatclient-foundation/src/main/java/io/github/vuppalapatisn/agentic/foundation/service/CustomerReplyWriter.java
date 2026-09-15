package io.github.vuppalapatisn.agentic.foundation.service;

import io.github.vuppalapatisn.agentic.foundation.advisor.RunContextAdvisor;
import io.github.vuppalapatisn.agentic.foundation.domain.OrderSummary;
import io.github.vuppalapatisn.agentic.foundation.domain.RefundDecision;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Drafts the customer-facing message.
 *
 * <p>Two things worth copying here:
 *
 * <ol>
 *   <li><b>Memory is scoped by conversation id</b>, passed explicitly. Memory that defaults to a
 *       global key is a cross-tenant leak waiting to happen.</li>
 *   <li><b>The draft is never sent from here.</b> Sending is an {@code E2} irreversible egress and
 *       lives behind a gate in project 02. A drafting service that can also send is a
 *       mixed-boundary component — exactly what Phase 2 forbids.</li>
 * </ol>
 */
@Service
public class CustomerReplyWriter {

    private static final Resource USER_PROMPT = new ClassPathResource("prompts/customer-reply.st");

    private final ChatClient replyChatClient;

    CustomerReplyWriter(ChatClient replyChatClient) {
        this.replyChatClient = replyChatClient;
    }

    public String draft(String runId, String conversationId, OrderSummary order, RefundDecision decision) {
        return request(runId, conversationId, order, decision).call().content();
    }

    /** Streaming variant — use it whenever a human is waiting on the output. */
    public Flux<String> streamDraft(String runId, String conversationId, OrderSummary order, RefundDecision decision) {
        return request(runId, conversationId, order, decision).stream().content();
    }

    private ChatClient.ChatClientRequestSpec request(String runId,
                                                     String conversationId,
                                                     OrderSummary order,
                                                     RefundDecision decision) {
        return replyChatClient.prompt()
                .advisors(a -> a
                        .param(ChatMemory.CONVERSATION_ID, conversationId)
                        .param(RunContextAdvisor.RUN_ID, runId)
                        .param(RunContextAdvisor.STEP, "customer-reply"))
                .user(u -> u.text(USER_PROMPT)
                        .param("orderId", order.orderId())
                        .param("itemDescription", order.itemDescription())
                        .param("outcome", decision.outcome().name())
                        .param("rationale", decision.rationale() == null ? "" : decision.rationale()));
    }
}
