package io.github.vuppalapatisn.agentic.mcpclient.web;

import io.github.vuppalapatisn.agentic.mcpclient.service.RemoteRefundAgent;
import io.github.vuppalapatisn.agentic.mcpclient.trust.ToolAdmissionLog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
class RemoteAgentController {

    record HandleRequest(
            @NotBlank @Pattern(regexp = "[A-Z]-\\d{4}") String orderId,
            @NotBlank @Size(max = 8_000) String message) {
    }

    private final RemoteRefundAgent agent;
    private final ToolAdmissionLog admissionLog;

    RemoteAgentController(RemoteRefundAgent agent, ToolAdmissionLog admissionLog) {
        this.agent = agent;
        this.admissionLog = admissionLog;
    }

    @PostMapping("/refunds/handle")
    RemoteRefundAgent.AgentReply handle(@Valid @RequestBody HandleRequest request) {
        return agent.handle(request.orderId(), request.message());
    }

    /**
     * Every admission decision, with the fingerprint the server actually published.
     *
     * <p>This is the endpoint you use to pin a new server: connect with a blank fingerprint, read
     * the observed value here, review the definition, then put the hash in configuration and turn
     * {@code fail-closed-on-change} on.
     */
    @GetMapping("/mcp/tools")
    List<ToolAdmissionLog.Admission> admissions() {
        return admissionLog.admissions();
    }

    /** Tools currently withheld — the review queue. */
    @GetMapping("/mcp/withheld")
    List<ToolAdmissionLog.Admission> withheld() {
        return admissionLog.withheld();
    }
}
