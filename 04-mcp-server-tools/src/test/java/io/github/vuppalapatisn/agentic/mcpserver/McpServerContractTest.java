package io.github.vuppalapatisn.agentic.mcpserver;

import io.github.vuppalapatisn.agentic.mcpserver.tools.RefundMcpTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Guards the <b>published contract</b> of this server: the tool set, the honesty of the annotation
 * hints, and the absence of an approval tool.
 *
 * <p>These read like trivia until you remember that a client caches your tool list and may decide
 * what needs human confirmation from your hints. Changing either silently is a rug pull, which is
 * exactly what project 05 defends against from the other side.
 */
@SpringBootTest
@AutoConfigureMockMvc
class McpServerContractTest {

    @Autowired
    MockMvc mockMvc;

    private static List<Method> mcpTools() {
        return Arrays.stream(RefundMcpTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(McpTool.class))
                .toList();
    }

    @Test
    @DisplayName("the published tool set is exactly the four read/attempt tools")
    void publishedToolSet() {
        assertThat(mcpTools())
                .extracting(method -> method.getAnnotation(McpTool.class).name())
                .containsExactlyInAnyOrder("lookupOrder", "checkFraudSignal", "issueRefund", "getRefundStatus");
    }

    @Test
    @DisplayName("separation of duty: approval is NOT an MCP tool")
    void approvalIsNotExposedOverMcp() {
        assertThat(mcpTools())
                .extracting(method -> method.getAnnotation(McpTool.class).name())
                .noneMatch(name -> name.toLowerCase().contains("approve"));
    }

    @Test
    @DisplayName("the hints are honest: issueRefund is destructive and not read-only")
    void issueRefundHintsAreHonest() {
        McpTool tool = mcpTools().stream()
                .filter(method -> method.getAnnotation(McpTool.class).name().equals("issueRefund"))
                .findFirst().orElseThrow()
                .getAnnotation(McpTool.class);

        assertThat(tool.annotations().readOnlyHint()).isFalse();
        assertThat(tool.annotations().destructiveHint()).isTrue();
        // Repeated calls are deduplicated by a server-derived idempotency key.
        assertThat(tool.annotations().idempotentHint()).isTrue();
    }

    @Test
    @DisplayName("read tools declare themselves read-only and non-destructive")
    void readToolHintsAreHonest() {
        for (String name : List.of("lookupOrder", "checkFraudSignal", "getRefundStatus")) {
            McpTool tool = mcpTools().stream()
                    .filter(method -> method.getAnnotation(McpTool.class).name().equals(name))
                    .findFirst().orElseThrow()
                    .getAnnotation(McpTool.class);

            assertThat(tool.annotations().readOnlyHint()).as(name).isTrue();
            assertThat(tool.annotations().destructiveHint()).as(name).isFalse();
        }
    }

    @Test
    @DisplayName("no tool takes an amount or a recipient — values the caller must not choose")
    void noValueParameters() {
        assertThat(mcpTools()).allSatisfy(method ->
                assertThat(Arrays.stream(method.getParameters()).map(p -> p.getName().toLowerCase()))
                        .as(method.getName())
                        .noneMatch(name -> name.contains("amount") || name.contains("email")
                                || name.contains("recipient")));
    }

    @Test
    @DisplayName("the MCP endpoint is mounted, and the admin approval surface is separate")
    void surfacesAreSeparate() throws Exception {
        // The MCP transport is mounted at /mcp: it rejects a malformed request rather than 404ing.
        int mcpStatus = mockMvc.perform(post("/mcp").content("{}")
                        .contentType("application/json"))
                .andReturn().getResponse().getStatus();
        assertThat(mcpStatus).isNotEqualTo(404);

        // The human surface is ordinary HTTP, on a different route, and is not part of MCP.
        mockMvc.perform(get("/admin/approvals"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
    }
}
