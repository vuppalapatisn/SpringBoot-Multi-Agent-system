package io.github.vuppalapatisn.agentic.multiagent.testsupport;

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

/** Queued responses, plus a record of what each agent was shown — which is how taint is asserted. */
public class ScriptedChatModel implements ChatModel {

    private final Deque<String> answers = new ArrayDeque<>();
    private final List<String> prompts = new ArrayList<>();

    public ScriptedChatModel enqueue(String... texts) {
        for (String text : texts) {
            answers.addLast(text);
        }
        return this;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        StringBuilder text = new StringBuilder();
        prompt.getInstructions().forEach(message -> text.append(message.getText()).append('\n'));
        prompts.add(text.toString());

        String answer = answers.isEmpty() ? "" : answers.removeFirst();
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(answer),
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
        return prompts.size();
    }

    public List<String> prompts() {
        return List.copyOf(prompts);
    }

    public String prompt(int index) {
        return prompts.get(index);
    }

    public void reset() {
        answers.clear();
        prompts.clear();
    }
}
