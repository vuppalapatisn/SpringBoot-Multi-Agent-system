package io.github.vuppalapatisn.agentic.mcpclient.trust;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolsChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records every admission decision, and reacts to a server changing its tool list.
 *
 * <p>The {@link McpToolsChangedEvent} listener is the rug-pull alarm. A server may legitimately
 * publish new tools, and Spring AI will invalidate the client's cache so they are picked up — which
 * is convenient and is also exactly the risk. The tools you reviewed on Monday are not necessarily
 * the tools you are calling on Friday, so the change is <b>logged, counted and alerted on</b>, and
 * each new definition still has to pass {@link RemoteToolPolicy} before any model sees it.
 */
@Component
public class ToolAdmissionLog {

    private static final Logger log = LoggerFactory.getLogger(ToolAdmissionLog.class);

    public record Admission(String server, String tool, RemoteToolPolicy.Verdict verdict,
                            String detail, String observedFingerprint, Instant at) {
    }

    private final List<Admission> admissions = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> toolListSizeByServer = new ConcurrentHashMap<>();
    private final MeterRegistry meters;

    public ToolAdmissionLog(MeterRegistry meters) {
        this.meters = meters;
    }

    public void record(String server, RemoteToolPolicy.Decision decision) {
        admissions.add(new Admission(server, decision.toolName(), decision.verdict(),
                decision.detail(), decision.observedFingerprint(), Instant.now()));
        meters.counter("agentic.mcp.tool.admission",
                "server", server, "tool", decision.toolName(),
                "verdict", decision.verdict().name()).increment();
    }

    /** The rug-pull alarm. Alert on this; do not merely log it. */
    @EventListener
    public void onToolsChanged(McpToolsChangedEvent event) {
        String server = event.getConnectionName();
        Integer previous = toolListSizeByServer.put(server, event.getTools().size());
        meters.counter("agentic.mcp.tool.list.changed", "server", server).increment();
        log.warn("MCP server '{}' changed its tool list ({} -> {} tools): {}. "
                        + "Definitions must be re-reviewed and re-pinned.",
                server, previous == null ? "unknown" : previous, event.getTools().size(),
                event.getTools().stream().map(io.modelcontextprotocol.spec.McpSchema.Tool::name).toList());
    }

    public List<Admission> admissions() {
        return List.copyOf(admissions);
    }

    /** Tools currently withheld — what a human needs to review. */
    public List<Admission> withheld() {
        return admissions.stream()
                .filter(admission -> admission.verdict() != RemoteToolPolicy.Verdict.ALLOWED
                        && admission.verdict() != RemoteToolPolicy.Verdict.UNPINNED)
                .toList();
    }
}
