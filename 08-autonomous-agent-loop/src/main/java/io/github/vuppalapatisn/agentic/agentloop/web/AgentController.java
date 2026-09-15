package io.github.vuppalapatisn.agentic.agentloop.web;

import io.github.vuppalapatisn.agentic.agentloop.domain.Domain.AgentRunResult;
import io.github.vuppalapatisn.agentic.agentloop.gate.GuardedPayout;
import io.github.vuppalapatisn.agentic.agentloop.service.RefundAgent;
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
class AgentController {

    record RunRequest(
            @NotBlank @Pattern(regexp = "[A-Z]-\\d{4}") String orderId,
            @NotBlank @Size(max = 8_000) String message) {
    }

    private final RefundAgent agent;
    private final GuardedPayout guardedPayout;

    AgentController(RefundAgent agent, GuardedPayout guardedPayout) {
        this.agent = agent;
        this.guardedPayout = guardedPayout;
    }

    /**
     * Every response carries the budget readout — steps, tool calls, tokens, cost, elapsed — and
     * the step trace. In an agent architecture that is not telemetry, it is the receipt: it is the
     * only way to know what a run actually did.
     */
    @PostMapping("/refunds/run")
    AgentRunResult run(@Valid @RequestBody RunRequest request) {
        return agent.handle(request.orderId(), request.message());
    }

    @GetMapping("/approvals")
    List<GuardedPayout.PendingApproval> pending() {
        return guardedPayout.pendingApprovals();
    }
}
