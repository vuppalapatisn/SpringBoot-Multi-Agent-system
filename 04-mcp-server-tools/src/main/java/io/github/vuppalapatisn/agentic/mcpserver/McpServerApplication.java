package io.github.vuppalapatisn.agentic.mcpserver;

import io.github.vuppalapatisn.agentic.mcpserver.config.ServerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Project 04 — an MCP server that enforces its own policy.
 *
 * <p>The tool boundary now spans a network and a trust boundary: the caller is an agent you do not
 * control, running a prompt you have not read. Every control is applied here rather than assumed
 * there. See {@code docs/CFG.md}.
 */
@SpringBootApplication
@EnableConfigurationProperties(ServerProperties.class)
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
