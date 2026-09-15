package io.github.vuppalapatisn.agentic.workflow.testsupport;

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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Queued responses, in call order, so a workflow's exact model-call sequence is assertable. */
public class ScriptedChatModel implements ChatModel {

    private final Deque<String> answers = new ArrayDeque<>();
    private final List<Prompt> received = new ArrayList<>();

    public ScriptedChatModel enqueue(String... texts) {
        for (String text : texts) {
            answers.addLast(text);
        }
        return this;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        received.add(prompt);
        String text = answers.isEmpty() ? "" : answers.removeFirst();
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text),
                        ChatGenerationMetadata.builder().finishReason("end_turn").build())),
                ChatResponseMetadata.builder()
                        .id("scripted").model("scripted-model")
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

    public int callCount() {
        return received.size();
    }

    public String promptText(int index) {
        StringBuilder text = new StringBuilder();
        received.get(index).getInstructions().forEach(message -> text.append(message.getText()).append('\n'));
        return text.toString();
    }
}
