package io.github.vuppalapatisn.agentic.foundation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the provider is swappable, and that swapping it needs no code change.
 *
 * <p>Two claims this repository makes in prose, asserted here instead:
 *
 * <ol>
 *   <li><b>Gemini can replace Anthropic by configuration alone.</b> Nothing in {@code src/main}
 *       imports a provider-specific type; every {@code ChatClient} is built with the neutral
 *       {@code ChatOptions.builder()}. Selecting {@code google-genai} therefore produces a working
 *       context with the same beans.</li>
 *   <li><b>Only the selected provider's key is required.</b> Note what is missing below: there is
 *       no {@code spring.ai.anthropic.api-key}. The Anthropic auto-configuration is inactive, so
 *       its properties are never bound and its {@code ${ANTHROPIC_API_KEY}} placeholder is never
 *       resolved. A Gemini-only deployment does not need an Anthropic key, and if that stopped
 *       being true this test would fail with an unresolved-placeholder error.</li>
 * </ol>
 *
 * <p>The key below is fake. Constructing the client does not call Google; only a real request
 * would, and this test makes none.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=google-genai",
        "spring.ai.google.genai.api-key=AIzaSyFAKE-key-used-only-to-wire-the-context",
        "spring.ai.google.genai.chat.options.model=gemini-2.5-flash",
        "spring.ai.google.genai.chat.options.max-output-tokens=1024"
})
class GeminiProviderTest {

    @Autowired
    ChatModel chatModel;

    @Autowired
    ChatClient classifierChatClient;

    @Autowired
    ChatClient replyChatClient;

    @Test
    @DisplayName("selecting google-genai makes Gemini the active ChatModel")
    void geminiIsTheActiveProvider() {
        assertThat(chatModel).isInstanceOf(GoogleGenAiChatModel.class);
    }

    @Test
    @DisplayName("the application's own ChatClient beans are built against it unchanged")
    void theApplicationWiresUpAgainstGemini() {
        // Both are built in ChatClientConfig with ChatOptions.builder() - no provider-specific
        // type appears anywhere in src/main, which is why this works.
        assertThat(classifierChatClient).isNotNull();
        assertThat(replyChatClient).isNotNull();
    }

    @Test
    @DisplayName("Gemini's options implement the two interfaces the repo depends on")
    void geminiSupportsToolCallingAndStructuredOutput() {
        // This is the compatibility question that actually matters. Tool calling is skipped
        // entirely by ToolCallingAdvisor unless the model's options implement
        // ToolCallingChatOptions (see .claude/projects/00-index.md), and .entity() structured
        // output relies on StructuredOutputChatOptions. AnthropicChatOptions implements both;
        // so does GoogleGenAiChatOptions, which is why projects 02, 05, 08 and 09 work on either.
        assertThat(chatModel.getOptions())
                .isInstanceOf(ToolCallingChatOptions.class)
                .isInstanceOf(StructuredOutputChatOptions.class);
    }

    @Test
    @DisplayName("the configured Gemini model and token ceiling are bound")
    void configurationIsBound() {
        assertThat(chatModel.getOptions().getModel()).isEqualTo("gemini-2.5-flash");
        // Gemini calls this max-output-tokens; it surfaces through the neutral getMaxTokens().
        assertThat(chatModel.getOptions().getMaxTokens()).isEqualTo(1024);
    }
}
