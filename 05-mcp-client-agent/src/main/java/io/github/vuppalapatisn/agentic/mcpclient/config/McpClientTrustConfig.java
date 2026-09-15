package io.github.vuppalapatisn.agentic.mcpclient.config;

import io.github.vuppalapatisn.agentic.mcpclient.trust.RemoteToolPolicy;
import io.github.vuppalapatisn.agentic.mcpclient.trust.ToolAdmissionLog;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/**
 * Wires the trust boundary into Spring AI's MCP client.
 *
 * <p>{@link McpToolFilter} is the hook that matters: it decides, per connection and per tool,
 * whether a remote tool is even converted into a {@code ToolCallback} the model can see. Filtering
 * here rather than later is deliberate — a tool the model never sees cannot be called, and cannot
 * inject anything into the context window through its description.
 */
@Configuration(proxyBeanMethods = false)
public class McpClientTrustConfig {

    @Bean
    RemoteToolPolicy remoteToolPolicy(McpTrustProperties properties) {
        return new RemoteToolPolicy(properties.trustedTools(), properties.failClosedOnChange());
    }

    /**
     * Deny by default. Every tool from every server passes through here, and the decision is
     * recorded so a human can see what was withheld and why.
     */
    @Bean
    McpToolFilter mcpToolFilter(RemoteToolPolicy policy, ToolAdmissionLog admissionLog) {
        return (connectionInfo, tool) -> {
            String server = serverName(connectionInfo);
            RemoteToolPolicy.Decision decision = policy.evaluate(server, tool);
            admissionLog.record(server, decision);
            return decision.allowed();
        };
    }

    /**
     * Prefix tool names per server. Two servers can both publish {@code issueRefund}; without a
     * prefix the model sees one name and calls whichever the client happened to register last.
     */
    @Bean
    McpToolNamePrefixGenerator mcpToolNamePrefixGenerator(McpTrustProperties properties) {
        return (connectionInfo, tool) -> properties.prefixToolNames()
                ? serverName(connectionInfo) + "_" + tool.name()
                : tool.name();
    }

    @Bean
    ChatClient remoteAgentChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(new ClassPathResource("prompts/remote-agent-system.st"))
                .defaultOptions(ChatOptions.builder().temperature(0.0d).maxTokens(1024))
                .defaultAdvisors(SimpleLoggerAdvisor.builder().build())
                .build();
    }

    private static String serverName(org.springframework.ai.mcp.McpConnectionInfo connectionInfo) {
        if (connectionInfo == null || connectionInfo.initializeResult() == null
                || connectionInfo.initializeResult().serverInfo() == null) {
            return "unknown";
        }
        return connectionInfo.initializeResult().serverInfo().name();
    }
}
