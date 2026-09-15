package io.github.vuppalapatisn.agentic.mcpserver.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Server-side policy. Every value here is re-applied on the server, whatever an MCP client believes.
 *
 * @param executionEnabled          kill switch: when false, no refund executes, but reads still work
 * @param autoApproveBelowMinor     automatic tier ceiling
 * @param dualControlAboveMinor     two-approver floor
 * @param maxRefundAgeDays          policy window
 * @param maxRefundAttemptsPerOrder server-side rate limit on the one-way door
 */
@Validated
@ConfigurationProperties(prefix = "agentic.mcp-server")
public record ServerProperties(

        boolean executionEnabled,

        @Min(0) long autoApproveBelowMinor,

        @Min(0) long dualControlAboveMinor,

        @Min(1) int maxRefundAgeDays,

        @Min(1) int maxRefundAttemptsPerOrder) {
}
