package io.github.vuppalapatisn.agentic.statemachine.testsupport;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Test doubles shared by this project's tests. */
public final class TestSupport {

    private TestSupport() {
    }

    /** A clock you can move, so expiry and the compensation window are testable. */
    public static class MutableClock extends Clock {

        private Instant now;
        private final ZoneId zone;

        public MutableClock(Instant now) {
            this(now, ZoneId.of("UTC"));
        }

        private MutableClock(Instant now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        public void advance(Duration amount) {
            now = now.plus(amount);
        }

        /** Rewind to a known instant between tests that share one application context. */
        public void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new MutableClock(now, newZone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Queued classification responses; counts calls so "the model got no second turn" is provable. */
    public static class ScriptedChatModel implements ChatModel {

        private final Deque<String> answers = new ArrayDeque<>();
        private int calls;

        public ScriptedChatModel enqueue(String... texts) {
            for (String text : texts) {
                answers.addLast(text);
            }
            return this;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls++;
            String text = answers.isEmpty() ? "" : answers.removeFirst();
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage(text),
                            ChatGenerationMetadata.builder().finishReason("end_turn").build())),
                    ChatResponseMetadata.builder().id("scripted").model("scripted-model")
                            .usage(new DefaultUsage(150, 50)).build());
        }

        @Override
        public ChatOptions getOptions() {
            return ChatOptions.builder().model("scripted-model").build();
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return getOptions();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        public int calls() {
            return calls;
        }

        public void reset() {
            answers.clear();
            calls = 0;
        }
    }

    /** A scripted classification payload. */
    public static String classification(String outcome, String clauseId, long amountMinor) {
        return """
                {"outcome":"%s","clauseId":"%s","proposedAmountMinor":%d,"risk":"LOW",
                 "rationale":"scripted"}
                """.formatted(outcome, clauseId, amountMinor);
    }
}
