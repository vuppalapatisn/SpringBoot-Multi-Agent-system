package io.github.vuppalapatisn.agentic.mcpclient;

import io.github.vuppalapatisn.agentic.mcpclient.config.McpTrustProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Project 05 — an agent whose tools arrive over MCP from a server it does not operate.
 *
 * <p>The tool boundary is now also a trust boundary, and three things you normally control belong
 * to someone else: the tool description (text injected into your context), the schema, and the tool
 * list itself. See {@code docs/CFG.md}.
 */
@SpringBootApplication
@EnableConfigurationProperties(McpTrustProperties.class)
public class McpClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpClientApplication.class, args);
    }
}
