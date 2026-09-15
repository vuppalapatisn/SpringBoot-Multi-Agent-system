package io.github.vuppalapatisn.agentic.tools.web;

import io.github.vuppalapatisn.agentic.tools.domain.RefundActionResult;
import io.github.vuppalapatisn.agentic.tools.gate.ApprovalException;
import io.github.vuppalapatisn.agentic.tools.gate.ApprovalStatus;
import io.github.vuppalapatisn.agentic.tools.gate.ApprovalStore;
import io.github.vuppalapatisn.agentic.tools.gate.FrozenEffectRunner;
import io.github.vuppalapatisn.agentic.tools.gate.PendingApproval;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The human side of the gate.
 *
 * <p>The approver is shown {@link PendingApproval#humanExplanation()} — text rendered by the policy
 * gate from trusted data. They are not shown, and do not approve, anything the model wrote.
 *
 * <p>On the final approval the effect runs immediately from the <b>frozen payload</b> via
 * {@link FrozenEffectRunner}. There is deliberately no "now ask the model to continue" step: that
 * step is where an approved $24 refund turns into a $2,400 one.
 */
@RestController
@RequestMapping("/api/approvals")
class ApprovalController {

    record DecisionRequest(@NotBlank String approver) {
    }

    record ApprovalView(String id, String runId, String tool, String businessKey, long amountMinor,
                        String rule, String humanExplanation, String payloadHash,
                        int requiredApprovals, List<PendingApproval.Approval> approvals,
                        ApprovalStatus status, String expiresAt) {

        static ApprovalView of(PendingApproval approval) {
            return new ApprovalView(approval.id(), approval.runId(), approval.toolName(),
                    approval.businessKey(), approval.amountMinor(), approval.rule(),
                    approval.humanExplanation(), approval.payloadHash(), approval.requiredApprovals(),
                    approval.approvals(), approval.status(), approval.expiresAt().toString());
        }
    }

    record DecisionResponse(ApprovalView approval, RefundActionResult effect) {
    }

    private final ApprovalStore approvals;
    private final FrozenEffectRunner frozenEffectRunner;

    ApprovalController(ApprovalStore approvals, FrozenEffectRunner frozenEffectRunner) {
        this.approvals = approvals;
        this.frozenEffectRunner = frozenEffectRunner;
    }

    @GetMapping
    List<ApprovalView> pending() {
        return approvals.pending().stream().map(ApprovalView::of).toList();
    }

    @GetMapping("/{id}")
    ResponseEntity<ApprovalView> one(@PathVariable String id) {
        return approvals.find(id)
                .map(ApprovalView::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/approve")
    DecisionResponse approve(@PathVariable String id, @Valid @RequestBody DecisionRequest request) {
        PendingApproval approval = approvals.approve(id, request.approver());
        if (approval.status() != ApprovalStatus.APPROVED) {
            // Dual control: still waiting for the second, distinct approver.
            return new DecisionResponse(ApprovalView.of(approval), null);
        }
        RefundActionResult effect = frozenEffectRunner.execute(approval);
        return new DecisionResponse(ApprovalView.of(approvals.find(id).orElse(approval)), effect);
    }

    @PostMapping("/{id}/reject")
    DecisionResponse reject(@PathVariable String id, @Valid @RequestBody DecisionRequest request) {
        return new DecisionResponse(ApprovalView.of(approvals.reject(id, request.approver())), null);
    }

    /** Every approval failure is a 409 with a typed reason, never a 500. */
    @ExceptionHandler(ApprovalException.class)
    ResponseEntity<Object> onApprovalFailure(ApprovalException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(java.util.Map.of("reason", ex.reason().name(), "message", ex.getMessage()));
    }
}
