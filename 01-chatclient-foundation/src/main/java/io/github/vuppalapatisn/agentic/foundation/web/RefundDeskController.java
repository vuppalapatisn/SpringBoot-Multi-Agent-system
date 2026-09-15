package io.github.vuppalapatisn.agentic.foundation.web;

import io.github.vuppalapatisn.agentic.foundation.audit.DecisionRecord;
import io.github.vuppalapatisn.agentic.foundation.audit.DecisionLog;
import io.github.vuppalapatisn.agentic.foundation.domain.OrderSummary;
import io.github.vuppalapatisn.agentic.foundation.domain.RefundDecision;
import io.github.vuppalapatisn.agentic.foundation.service.CustomerReplyWriter;
import io.github.vuppalapatisn.agentic.foundation.service.OrderDirectory;
import io.github.vuppalapatisn.agentic.foundation.service.RefundClassifier;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

/**
 * Read-only refund desk. Classifies and drafts; never pays, never sends.
 *
 * <p>The {@code advisoryOnly} flag in the response is not decoration — it is the contract that lets
 * a caller distinguish this endpoint from the one in project 02 that can actually move money.
 */
@RestController
@RequestMapping("/api/refunds")
class RefundDeskController {

    private final OrderDirectory orders;
    private final RefundClassifier classifier;
    private final CustomerReplyWriter replyWriter;
    private final DecisionLog decisionLog;

    RefundDeskController(OrderDirectory orders,
                         RefundClassifier classifier,
                         CustomerReplyWriter replyWriter,
                         DecisionLog decisionLog) {
        this.orders = orders;
        this.classifier = classifier;
        this.replyWriter = replyWriter;
        this.decisionLog = decisionLog;
    }

    @PostMapping("/classify")
    ResponseEntity<ClassifyResponse> classify(@Valid @RequestBody ClassifyRequest request) {
        String runId = newRunId();
        OrderSummary order = orders.find(request.orderId()).orElse(null);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }

        RefundDecision decision = classifier.classify(runId, order, request.message());
        String conversationId = request.conversationId() == null ? runId : request.conversationId();
        String draft = replyWriter.draft(runId, conversationId, order, decision);

        return ResponseEntity.ok(new ClassifyResponse(
                runId, decision, order.totalMinor(), true, draft));
    }

    /** Streaming variant: use when a human is waiting for the text. */
    @PostMapping(value = "/draft/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<String> streamDraft(@Valid @RequestBody ClassifyRequest request) {
        String runId = newRunId();
        OrderSummary order = orders.find(request.orderId()).orElseThrow(OrderNotFoundException::new);
        RefundDecision decision = classifier.classify(runId, order, request.message());
        String conversationId = request.conversationId() == null ? runId : request.conversationId();
        return replyWriter.streamDraft(runId, conversationId, order, decision);
    }

    /** Phase 9: the run must be answerable from stored data. */
    @GetMapping("/runs/{runId}/decisions")
    List<DecisionRecord> decisions(@PathVariable String runId) {
        return decisionLog.forRun(runId);
    }

    private static String newRunId() {
        return "r-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
