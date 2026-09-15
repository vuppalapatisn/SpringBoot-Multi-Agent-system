package io.github.vuppalapatisn.agentic.agentloop.testsupport;

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
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * A scripted model that can call tools, and can be told to repeat its last action forever — which
 * is how a runaway agent is simulated without hoping a real model misbehaves on cue.
 */
public class ScriptedChatModel implements ChatModel {

    private sealed interface Step {
        record Text(String content) implements Step {
        }

        record Call(String tool, String argumentsJson) implements Step {
        }
    }

    private final Deque<Step> steps = new ArrayDeque<>();
    private Step repeatForever;
    private int calls;
    private int promptTokens = 200;
    private int completionTokens = 60;

    public ScriptedChatModel thenSay(String content) {
        steps.addLast(new Step.Text(content));
        return this;
    }

    public ScriptedChatModel thenCall(String tool, String argumentsJson) {
        steps.addLast(new Step.Call(tool, argumentsJson));
        return this;
    }

    /** The runaway: after the scripted steps, keep asking for this tool call indefinitely. */
    public ScriptedChatModel thenRepeatForever(String tool, String argumentsJson) {
        repeatForever = new Step.Call(tool, argumentsJson);
        return this;
    }

    public ScriptedChatModel withUsage(int prompt, int completion) {
        this.promptTokens = prompt;
        this.completionTokens = completion;
        return this;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        calls++;
        Step step = steps.isEmpty()
                ? (repeatForever == null ? new Step.Text("") : repeatForever)
                : steps.removeFirst();
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
                ChatResponseMetadata.builder().id("scripted").model("scripted-model")
                        .usage(new DefaultUsage(promptTokens, completionTokens)).build());
    }

    /**
     * Load-bearing: Spring AI decides whether tool calling is possible from the <b>model's</b>
     * options type. Plain {@code ChatOptions} makes the tool loop silently skip.
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

    public int calls() {
        return calls;
    }

    public void reset() {
        steps.clear();
        repeatForever = null;
        calls = 0;
        promptTokens = 200;
        completionTokens = 60;
    }
}
