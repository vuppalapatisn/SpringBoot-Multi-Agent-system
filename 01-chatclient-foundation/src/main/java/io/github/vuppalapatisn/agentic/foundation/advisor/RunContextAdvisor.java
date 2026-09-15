package io.github.vuppalapatisn.agentic.foundation.advisor;

import io.github.vuppalapatisn.agentic.foundation.audit.DecisionLog;
import io.github.vuppalapatisn.agentic.foundation.audit.DecisionRecord;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Cross-cutting run context and usage accounting.
 *
 * <p>This is the correct use of an advisor: it wraps the <b>model call</b> and observes it. Compare
 * with {@code GuardedToolExecutor} in project 02 — an approval gate must <b>not</b> be an advisor,
 * because an advisor only sees calls that go through the {@code ChatClient}, and a tool bean can be
 * invoked directly.
 *
 * <p>Reads {@code runId} and {@code step} from the request context, which callers set with
 * {@code .advisors(a -> a.param("runId", id))}.
 */
public class RunContextAdvisor implements BaseAdvisor {

    public static final String RUN_ID = "runId";
    public static final String STEP = "step";
    private static final String STARTED_AT = "runContextAdvisor.startedAt";

    private final DecisionLog decisionLog;
    private final int order;

    public RunContextAdvisor(DecisionLog decisionLog, int order) {
        this.decisionLog = decisionLog;
        this.order = order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String runId = stringContext(request, RUN_ID, "unattributed");
        MDC.put(RUN_ID, runId);
        return request.mutate()
                .context(STARTED_AT, System.nanoTime())
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        try {
            decisionLog.append(toRecord(response));
        }
        finally {
            MDC.remove(RUN_ID);
        }
        return response;
    }

    private DecisionRecord toRecord(ChatClientResponse response) {
        ChatResponse chatResponse = response.chatResponse();
        Object started = response.context().get(STARTED_AT);
        Duration took = started instanceof Long nanos
                ? Duration.ofNanos(System.nanoTime() - nanos)
                : Duration.ZERO;

        String model = null;
        Usage usage = null;
        String finishReason = null;
        String output = null;
        if (chatResponse != null) {
            if (chatResponse.getMetadata() != null) {
                model = chatResponse.getMetadata().getModel();
                usage = chatResponse.getMetadata().getUsage();
            }
            if (chatResponse.getResult() != null) {
                output = chatResponse.getResult().getOutput() == null
                        ? null : chatResponse.getResult().getOutput().getText();
                if (chatResponse.getResult().getMetadata() != null) {
                    finishReason = chatResponse.getResult().getMetadata().getFinishReason();
                }
            }
        }

        return new DecisionRecord(
                stringContext(response, RUN_ID, "unattributed"),
                0,
                stringContext(response, STEP, "chat"),
                model,
                hash(output),
                output,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                finishReason,
                took,
                Instant.now());
    }

    private static String hash(String value) {
        return value == null ? null : DigestUtils.md5DigestAsHex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String stringContext(ChatClientRequest request, String key, String fallback) {
        Object value = request.context().get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static String stringContext(ChatClientResponse response, String key, String fallback) {
        Object value = response.context().get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    @Override
    public String getName() {
        return "runContext";
    }

    @Override
    public int getOrder() {
        return order;
    }
}
