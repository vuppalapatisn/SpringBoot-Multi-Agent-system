package io.github.vuppalapatisn.agentic.rag.service;

import io.github.vuppalapatisn.agentic.rag.config.RagProperties;
import io.github.vuppalapatisn.agentic.rag.domain.GroundedAnswer;
import io.github.vuppalapatisn.agentic.rag.gate.GroundednessGate;
import io.github.vuppalapatisn.agentic.rag.ingest.PolicyIngestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Answers policy questions from the indexed corpus, then checks the answer.
 *
 * <p>Two controls that are easy to leave out and expensive to leave out:
 *
 * <ol>
 *   <li><b>Tenant filter on every query.</b> The filter expression is derived from the caller's
 *       tenant, never from the question. Without it, retrieval is a cross-tenant read, and no
 *       amount of prompt instruction fixes a query that returned the wrong customer's document.</li>
 *   <li><b>The retrieved documents are inspected after the call</b> via the advisor's context key,
 *       and handed to the gate. An answer is only as good as what was actually retrieved, so the
 *       code that judges it needs to see both.</li>
 * </ol>
 */
@Service
public class PolicyAnswerService {

    private static final Logger log = LoggerFactory.getLogger(PolicyAnswerService.class);

    private final ChatClient policyChatClient;
    private final RetrievalAugmentationAdvisor retrievalAdvisor;
    private final GroundednessGate groundednessGate;
    private final RagProperties properties;

    public PolicyAnswerService(ChatClient policyChatClient,
                               RetrievalAugmentationAdvisor retrievalAdvisor,
                               GroundednessGate groundednessGate,
                               RagProperties properties) {
        this.policyChatClient = policyChatClient;
        this.retrievalAdvisor = retrievalAdvisor;
        this.groundednessGate = groundednessGate;
        this.properties = properties;
    }

    public GroundedAnswer answer(String tenant, String question) {
        // Validated before the try block on purpose: an invalid tenant is a caller error, and
        // turning it into a polite refusal would hide a misconfigured or hostile client.
        String safeTenant = sanitiseTenant(tenant);
        String bounded = bound(question);
        try {
            ChatClientResponse response = policyChatClient.prompt()
                    .advisors(retrievalAdvisor)
                    .advisors(advisor -> advisor.param(
                            VectorStoreDocumentRetriever.FILTER_EXPRESSION,
                            // Built from the authenticated tenant, never from the question.
                            "%s == '%s'".formatted(PolicyIngestion.TENANT, safeTenant)))
                    .user(bounded)
                    .call()
                    .chatClientResponse();

            List<Document> retrieved = retrievedDocuments(response);
            String text = response.chatResponse() == null || response.chatResponse().getResult() == null
                    ? "" : response.chatResponse().getResult().getOutput().getText();

            GroundedAnswer checked = groundednessGate.check(text, retrieved);
            if (!checked.grounded()) {
                log.info("groundedness gate refused an answer: {}", checked.gateVerdict());
            }
            return checked;
        }
        catch (RuntimeException ex) {
            log.warn("policy answer failed: {}", ex.toString());
            return GroundedAnswer.refused("ANSWER_UNAVAILABLE:" + ex.getClass().getSimpleName(), List.of());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Document> retrievedDocuments(ChatClientResponse response) {
        Object documents = response.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        return documents instanceof List<?> list ? (List<Document>) list : List.of();
    }

    private String bound(String question) {
        if (question == null) {
            return "";
        }
        int limit = properties.maxQuestionChars();
        return question.length() <= limit ? question : question.substring(0, limit);
    }

    /** Tenant ids are opaque identifiers; anything else is rejected rather than escaped. */
    private static String sanitiseTenant(String tenant) {
        if (tenant == null || !tenant.matches("[a-zA-Z0-9_-]{1,40}")) {
            throw new IllegalArgumentException("invalid tenant identifier");
        }
        return tenant;
    }
}
