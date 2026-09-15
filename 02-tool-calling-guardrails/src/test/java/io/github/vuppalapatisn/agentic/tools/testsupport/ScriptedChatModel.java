package io.github.vuppalapatisn.agentic.tools.testsupport;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * A {@link ChatModel} that returns queued responses, including <b>tool calls</b>.
 *
 * <p>Scripting tool calls is what lets us test the thing that actually matters: that a model asking
 * for an irreversible action gets refused by the guard rather than obeyed. No network, no API key,
 * deterministic in CI.
 */
public class ScriptedChatModel implements ChatModel {

    private sealed interface Step {
        record Text(String content) implements Step {
        }

        record Call(String tool, String argumentsJson) implements Step {
        }
    }

    private final Deque<Step> steps = new ArrayDeque<>();
    private final List<Prompt> received = new ArrayList<>();

    public ScriptedChatModel thenSay(String content) {
        steps.addLast(new Step.Text(content));
        return this;
    }

    public ScriptedChatModel thenCall(String tool, String argumentsJson) {
        steps.addLast(new Step.Call(tool, argumentsJson));
        return this;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        received.add(prompt);
        Step step = steps.isEmpty() ? new Step.Text("") : steps.removeFirst();
        return switch (step) {
            case Step.Text text -> response(new AssistantMessage(text.content()), "end_turn");
            case Step.Call call -> response(AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "tc-" + UUID.randomUUID().toString().substring(0, 6),
                            "function", call.tool(), call.argumentsJson())))
                    .build(), "tool_use");
        };
    }

    private ChatResponse response(AssistantMessage message, String finishReason) {
        return new ChatResponse(
                List.of(new Generation(message,
                        ChatGenerationMetadata.builder().finishReason(finishReason).build())),
                ChatResponseMetadata.builder()
                        .id("scripted")
                        .model("scripted-model")
                        .usage(new DefaultUsage(100, 25))
                        .build());
    }

    /**
     * <b>Load-bearing.</b> {@code DefaultChatClientUtils} builds the request's options from
     * {@code chatModel.getOptions().mutate()}, and attaches tool callbacks only when that builder
     * is a {@link ToolCallingChatOptions.Builder}. {@code ToolCallingAdvisor} then skips the whole
     * tool loop unless the prompt's options implement {@link ToolCallingChatOptions}.
     *
     * <p>So the <i>model</i> decides whether tool calling is possible, not the client's
     * {@code defaultOptions}. Real providers satisfy this (for example {@code AnthropicChatOptions}
     * implements {@code ToolCallingChatOptions}); a stub that returns plain {@code ChatOptions}
     * looks like a model that silently ignores every tool, which is a confusing way to spend an
     * afternoon.
     */
    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().model("scripted-model").build();
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return getOptions();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    public int callCount() {
        return received.size();
    }

    public Prompt lastPrompt() {
        return received.get(received.size() - 1);
    }

    public List<Prompt> received() {
        return List.copyOf(received);
    }
}
