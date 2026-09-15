package io.github.vuppalapatisn.agentic.multiagent.web;

import io.github.vuppalapatisn.agentic.multiagent.authority.AgentCapabilities;
import io.github.vuppalapatisn.agentic.multiagent.domain.Domain.SupervisedRunResult;
import io.github.vuppalapatisn.agentic.multiagent.gate.GuardedPayout;
import io.github.vuppalapatisn.agentic.multiagent.supervisor.RefundSupervisor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
class SupervisorController {

    record RunRequest(
            @NotBlank @Pattern(regexp = "[A-Z]-\\d{4}") String orderId,
            @NotBlank @Size(max = 8_000) String message) {
    }

    private final RefundSupervisor supervisor;
    private final GuardedPayout guardedPayout;
    private final AgentCapabilities capabilities;

    SupervisorController(RefundSupervisor supervisor, GuardedPayout guardedPayout,
                         AgentCapabilities capabilities) {
        this.supervisor = supervisor;
        this.guardedPayout = guardedPayout;
        this.capabilities = capabilities;
    }

    /** The response carries the handoff sequence and the blackboard: who knew what, and when. */
    @PostMapping("/refunds/run")
    ResponseEntity<SupervisedRunResult> run(@Valid @RequestBody RunRequest request) {
        return supervisor.handle(request.orderId(), request.message())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The authority map, served as an endpoint. In a review this is the first thing to read: if a
     * second role appears with an effectful capability, the blast radius is no longer contained.
     */
    @GetMapping("/agents/authority")
    String authority() {
        return capabilities.describe();
    }

    @GetMapping("/approvals")
    List<GuardedPayout.PendingApproval> pending() {
        return guardedPayout.pendingApprovals();
    }
}
