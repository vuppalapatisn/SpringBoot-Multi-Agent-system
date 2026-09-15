package io.github.vuppalapatisn.agentic.foundation.testsupport;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A {@link ChatModel} that returns queued responses.
 *
 * <p>Every test in this repo uses it, so the whole build runs with no API key and no network. That
 * is not a convenience — a CI suite that calls a real model is neither deterministic nor a
 * regression test of <i>your</i> code.
 *
 * <p>It also records the prompts it received, which is what lets us assert the trust boundary:
 * untrusted customer text must appear in a user message and never in the system message.
 */
public class ScriptedChatModel implements ChatModel {

    private final Deque<String> scripted = new ArrayDeque<>();
    private final List<Prompt> received = new ArrayList<>();
    private RuntimeException failure;

    public ScriptedChatModel enqueue(String... responses) {
        for (String response : responses) {
            scripted.addLast(response);
        }
        return this;
    }

    /** Make the next call blow up, to exercise the failure path. */
    public ScriptedChatModel failWith(RuntimeException exception) {
        this.failure = exception;
        return this;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        received.add(prompt);
        if (failure != null) {
            RuntimeException toThrow = failure;
            failure = null;
            throw toThrow;
        }
        String text = scripted.isEmpty() ? "" : scripted.removeFirst();
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text),
                        ChatGenerationMetadata.builder().finishReason("end_turn").build())),
                ChatResponseMetadata.builder()
                        .id("scripted-1")
                        .model("scripted-model")
                        .usage(new DefaultUsage(120, 40))
                        .build());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    public List<Prompt> received() {
        return List.copyOf(received);
    }

    public Prompt lastPrompt() {
        if (received.isEmpty()) {
            throw new IllegalStateException("no prompt was received");
        }
        return received.get(received.size() - 1);
    }

    public int callCount() {
        return received.size();
    }
}
