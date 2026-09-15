package io.github.vuppalapatisn.agentic.statemachine.web;

import io.github.vuppalapatisn.agentic.statemachine.domain.Domain.RefundRun;
import io.github.vuppalapatisn.agentic.statemachine.domain.Domain.Transition;
import io.github.vuppalapatisn.agentic.statemachine.machine.RefundStateMachine;
import io.github.vuppalapatisn.agentic.statemachine.store.ApprovalRepository;
import io.github.vuppalapatisn.agentic.statemachine.store.RunRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
class StateMachineController {

    record StartRequest(
            @NotBlank @Pattern(regexp = "[A-Z]-\\d{4}") String orderId,
            @NotBlank @Size(max = 8_000) String message) {
    }

    record DecisionRequest(@NotBlank String approver) {
    }

    private final RefundStateMachine machine;
    private final RunRepository runs;
    private final ApprovalRepository approvals;

    StateMachineController(RefundStateMachine machine, RunRepository runs, ApprovalRepository approvals) {
        this.machine = machine;
        this.runs = runs;
        this.approvals = approvals;
    }

    @PostMapping("/refunds/start")
    ResponseEntity<RefundRun> start(@Valid @RequestBody StartRequest request) {
        return machine.start(request.orderId(), request.message())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/runs/{runId}")
    ResponseEntity<RefundRun> run(@PathVariable String runId) {
        return runs.find(runId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The audit trail a regulator asks for: every state change, with actor and reason. */
    @GetMapping("/runs/{runId}/transitions")
    List<Transition> transitions(@PathVariable String runId) {
        return runs.transitions(runId);
    }

    /**
     * Resume after a restart or a stall. Safe to call repeatedly — every move is a guarded
     * transition, so a duplicate call finds the run already moved and does nothing.
     */
    @PostMapping("/runs/{runId}/advance")
    ResponseEntity<RefundRun> advance(@PathVariable String runId) {
        return runs.find(runId).isEmpty()
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(machine.advance(runId));
    }

    @GetMapping("/approvals")
    List<ApprovalRepository.Approval> pending() {
        return approvals.pending();
    }

    @PostMapping("/approvals/{approvalId}/approve")
    ResponseEntity<RefundRun> approve(@PathVariable String approvalId,
                                      @Valid @RequestBody DecisionRequest request) {
        return machine.approve(approvalId, request.approver())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(409).build());
    }

    @PostMapping("/approvals/{approvalId}/reject")
    ResponseEntity<RefundRun> reject(@PathVariable String approvalId,
                                     @Valid @RequestBody DecisionRequest request) {
        return machine.reject(approvalId, request.approver())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(409).build());
    }
}
