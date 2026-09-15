package io.github.vuppalapatisn.agentic.workflow.web;

import io.github.vuppalapatisn.agentic.workflow.domain.Domain.RefundRunResult;
import io.github.vuppalapatisn.agentic.workflow.gate.ApprovalDesk;
import io.github.vuppalapatisn.agentic.workflow.orchestration.RefundWorkflow;
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
class WorkflowController {

    record RunRequest(
            @NotBlank @Pattern(regexp = "[A-Z]-\\d{4}") String orderId,
            @NotBlank @Size(max = 8_000) String message) {
    }

    record ApproveRequest(@NotBlank String approver) {
    }

    private final RefundWorkflow workflow;
    private final ApprovalDesk approvalDesk;

    WorkflowController(RefundWorkflow workflow, ApprovalDesk approvalDesk) {
        this.workflow = workflow;
        this.approvalDesk = approvalDesk;
    }

    @PostMapping("/refunds/run")
    ResponseEntity<RefundRunResult> run(@Valid @RequestBody RunRequest request) {
        return workflow.run(request.orderId(), request.message())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/approvals")
    List<ApprovalDesk.PendingApproval> pending() {
        return approvalDesk.pending();
    }

    /**
     * The resume entry point. Note that it is a <b>different request</b> from the one that started
     * the run — a workflow has nowhere to wait. Project 07 makes this a state transition instead.
     */
    @PostMapping("/approvals/{approvalId}/approve")
    ResponseEntity<RefundRunResult> approve(@PathVariable String approvalId,
                                            @Valid @RequestBody ApproveRequest request) {
        return workflow.resumeApproved(approvalId, request.approver())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(409).build());
    }
}
