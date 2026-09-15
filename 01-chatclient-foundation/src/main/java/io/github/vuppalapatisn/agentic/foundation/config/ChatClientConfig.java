package io.github.vuppalapatisn.agentic.foundation.config;

import io.github.vuppalapatisn.agentic.foundation.advisor.RunContextAdvisor;
import io.github.vuppalapatisn.agentic.foundation.audit.DecisionLog;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Two {@link ChatClient} beans, deliberately different.
 *
 * <ul>
 *   <li><b>refundClassifier</b> — temperature 0, no memory, structured output. A classifier must be
 *       as close to deterministic as the provider allows, and must not carry conversational state
 *       that could shift its judgement between runs.</li>
 *   <li><b>customerReplyWriter</b> — warmer, windowed memory, prose output. A different job, so a
 *       different client. Sharing one client between the two is a common mistake that makes the
 *       classifier drift.</li>
 * </ul>
 *
 * <p>Both put untrusted customer text in the <b>user</b> message only. Nothing untrusted reaches
 * {@code defaultSystem} — that is the prompt trust boundary (Phase 5).
 */
@Configuration(proxyBeanMethods = false)
public class ChatClientConfig {

    static final Resource CLASSIFIER_SYSTEM = new ClassPathResource("prompts/classifier-system.st");
    static final Resource REPLY_SYSTEM = new ClassPathResource("prompts/reply-system.st");

    /**
     * Windowed memory. An unbounded conversation is an unbounded prompt, which is an unbounded
     * cost — Phase 7 applies to context growth, not only to loops.
     */
    @Bean
    ChatMemory chatMemory(ChatMemoryRepository repository, FoundationProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(properties.memoryWindow())
                .build();
    }

    @Bean
    ChatClient classifierChatClient(ChatClient.Builder builder,
                                    FoundationProperties properties,
                                    DecisionLog decisionLog) {
        return builder.clone()
                .defaultSystem(CLASSIFIER_SYSTEM)
                .defaultOptions(ChatOptions.builder()
                        .model(properties.classifierModel())
                        .temperature(0.0d)
                        .maxTokens(properties.maxTokens()))
                .defaultAdvisors(new RunContextAdvisor(decisionLog, 0))
                .build();
    }

    @Bean
    ChatClient replyChatClient(ChatClient.Builder builder,
                               FoundationProperties properties,
                               ChatMemory chatMemory,
                               DecisionLog decisionLog) {
        return builder.clone()
                .defaultSystem(REPLY_SYSTEM)
                .defaultOptions(ChatOptions.builder()
                        .model(properties.classifierModel())
                        .temperature(0.4d)
                        .maxTokens(properties.maxTokens()))
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new RunContextAdvisor(decisionLog, 100),
                        SimpleLoggerAdvisor.builder().build())
                .build();
    }
}
