package io.github.vuppalapatisn.agentic.mcpclient.config;

import io.github.vuppalapatisn.agentic.mcpclient.trust.TrustedTool;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * The client's trust configuration: which remote tools may be offered to the model, from which
 * server, with which expected boundary class, pinned to which definition.
 *
 * <p>This is the artefact a security review reads. It is deliberately verbose: every remote tool is
 * a line someone signed off.
 *
 * @param failClosedOnChange when true, a tool whose definition no longer matches its pin is
 *                           withheld. Production should be true; leave it false only while pinning
 *                           a new server for the first time.
 * @param prefixToolNames    prefix tool names with the server name, so two servers publishing
 *                           {@code issueRefund} cannot collide in the model's tool list
 * @param allowedTools       the allowlist. Anything not here is never offered.
 */
@Validated
@ConfigurationProperties(prefix = "agentic.mcp-client")
public record McpTrustProperties(

        boolean failClosedOnChange,

        boolean prefixToolNames,

        @NotNull List<Entry> allowedTools) {

    /**
     * @param fingerprint hash of name + description + input schema at review time. Leave blank for
     *                    a first connection, read the observed value from
     *                    {@code GET /api/mcp/tools}, then pin it here.
     */
    public record Entry(
            @NotBlank String server,
            @NotBlank String name,
            @NotNull TrustedTool.BoundaryClass expectedClass,
            String fingerprint,
            @NotBlank String reviewedBy) {

        TrustedTool toTrustedTool() {
            return new TrustedTool(server, name, expectedClass, fingerprint, reviewedBy);
        }
    }

    public List<TrustedTool> trustedTools() {
        return allowedTools.stream().map(Entry::toTrustedTool).toList();
    }
}
