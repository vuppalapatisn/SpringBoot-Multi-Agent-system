package io.github.vuppalapatisn.agentic.tools.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

@Configuration(proxyBeanMethods = false)
public class AgentChatClientConfig {

    static final Resource AGENT_SYSTEM = new ClassPathResource("prompts/agent-system.st");

    /**
     * The tool-calling client. Note what is <b>not</b> here: no gate, no approval logic, no
     * allowlist. Those live inside the tool boundary, because an advisor-based control is bypassed
     * by any code path that does not go through this client.
     */
    @Bean
    ChatClient agentChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(AGENT_SYSTEM)
                // Note on Spring AI 2.x: the *model's* options type is what enables tool calling.
                // DefaultChatClientUtils builds the request options from
                // chatModel.getOptions().mutate() and only attaches tool callbacks when that
                // builder is a ToolCallingChatOptions.Builder; ToolCallingAdvisor then skips the
                // loop entirely unless the prompt's options implement ToolCallingChatOptions.
                // AnthropicChatOptions does. These values are merged into it.
                .defaultOptions(ChatOptions.builder()
                        .temperature(0.0d)
                        .maxTokens(1024))
                .defaultAdvisors(SimpleLoggerAdvisor.builder().build())
                .build();
    }
}
