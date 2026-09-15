package io.github.vuppalapatisn.agentic.tools.web;

import io.github.vuppalapatisn.agentic.tools.audit.AuditLog;
import io.github.vuppalapatisn.agentic.tools.audit.AuditRecord;
import io.github.vuppalapatisn.agentic.tools.boundary.ToolDescriptor;
import io.github.vuppalapatisn.agentic.tools.boundary.ToolRegistry;
import io.github.vuppalapatisn.agentic.tools.service.RefundAgent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
class RefundController {

    record HandleRequest(
            @NotBlank @Pattern(regexp = "[A-Z]-\\d{4}", message = "orderId must look like A-1187") String orderId,
            @NotBlank @Size(max = 8_000) String message) {
    }

    record HandleResponse(String runId, String reply, List<AuditRecord> audit) {
    }

    private final RefundAgent agent;
    private final AuditLog auditLog;
    private final ToolRegistry registry;

    RefundController(RefundAgent agent, AuditLog auditLog, ToolRegistry registry) {
        this.agent = agent;
        this.auditLog = auditLog;
        this.registry = registry;
    }

    @PostMapping("/refunds/handle")
    HandleResponse handle(@Valid @RequestBody HandleRequest request) {
        String runId = "r-" + UUID.randomUUID().toString().substring(0, 8);
        String reply = agent.handle(runId, request.orderId(), request.message());
        return new HandleResponse(runId, reply, auditLog.forRun(runId));
    }

    @GetMapping("/runs/{runId}/audit")
    List<AuditRecord> audit(@PathVariable String runId) {
        return auditLog.forRun(runId);
    }

    /**
     * The boundary inventory, served as an endpoint. Useful in a review: if a tool appears here
     * without a class, or an irreversible tool appears without compensation, the design changed.
     */
    @GetMapping("/tools")
    Collection<ToolDescriptor> tools() {
        return registry.all().values();
    }
}
