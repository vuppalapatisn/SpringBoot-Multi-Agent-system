package io.github.vuppalapatisn.agentic.mcpclient;

import io.github.vuppalapatisn.agentic.mcpclient.config.McpTrustProperties;
import io.github.vuppalapatisn.agentic.mcpclient.service.RemoteRefundAgent;
import io.github.vuppalapatisn.agentic.mcpclient.trust.TrustedTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context wiring with the MCP client switched off, which is also the degraded-mode test: no server,
 * therefore no tools, therefore the agent must explain that it cannot act rather than throw.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.anthropic.api-key=not-used-in-tests",
        // No MCP server is running in CI. The trust boundary is unit-tested in RemoteToolPolicyTest.
        "spring.ai.mcp.client.enabled=false"
})
class McpClientContextTest {

    @TestConfiguration
    static class StubModelConfig {

        @Bean
        ChatModel chatModel() {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    return new ChatResponse(List.of(new Generation(
                            new AssistantMessage("unused: the agent has no tools in this test"))));
                }

                @Override
                public ChatOptions getOptions() {
                    return ChatOptions.builder().model("stub").build();
                }

                @Override
                public Flux<ChatResponse> stream(Prompt prompt) {
                    return Flux.just(call(prompt));
                }
            };
        }
    }

    @Autowired
    RemoteRefundAgent agent;

    @Autowired
    McpTrustProperties trustProperties;

    @Autowired
    McpToolFilter toolFilter;

    @Test
    @DisplayName("with no MCP server reachable the agent degrades: it explains, it does not throw")
    void degradesWhenNoToolsAreAvailable() {
        RemoteRefundAgent.AgentReply reply = agent.handle("A-1187", "The parcel never arrived.");

        assertThat(reply.toolsAvailable()).isEmpty();
        assertThat(reply.reply()).contains("No refund tools are currently available");
    }

    @Test
    @DisplayName("the allowlist is configuration, and getRefundStatus is deliberately absent from it")
    void allowlistIsExplicit() {
        assertThat(trustProperties.trustedTools())
                .extracting(TrustedTool::name)
                .containsExactlyInAnyOrder("lookupOrder", "checkFraudSignal", "issueRefund")
                .doesNotContain("getRefundStatus");

        assertThat(trustProperties.trustedTools())
                .filteredOn(tool -> tool.name().equals("issueRefund"))
                .singleElement()
                .satisfies(tool -> {
                    assertThat(tool.expectedClass()).isEqualTo(TrustedTool.BoundaryClass.E2);
                    assertThat(tool.expectedClass().effectful()).isTrue();
                });
    }

    @Test
    @DisplayName("a tool filter is wired, so remote tools cannot reach the model unfiltered")
    void toolFilterIsWired() {
        assertThat(toolFilter).isNotNull();
    }
}
