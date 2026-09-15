package io.github.vuppalapatisn.agentic.mcpserver.web;

import io.github.vuppalapatisn.agentic.mcpserver.domain.Domain.RefundOutcome;
import io.github.vuppalapatisn.agentic.mcpserver.service.RefundDesk;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The human surface, on ordinary HTTP — <b>not</b> MCP.
 *
 * <p>This separation is the point. If approving were an MCP tool, the same agent that asked for the
 * refund could grant it, and every control in {@link RefundDesk} would be decorative. In a real
 * deployment this surface sits behind staff authentication on a different route, and the MCP
 * endpoint has no access to it.
 */
@RestController
@RequestMapping("/admin/approvals")
class ApprovalAdminController {

    record ApproveRequest(@NotBlank String approver) {
    }

    private final RefundDesk refundDesk;

    ApprovalAdminController(RefundDesk refundDesk) {
        this.refundDesk = refundDesk;
    }

    @GetMapping
    List<RefundDesk.PendingApproval> pending() {
        return refundDesk.pendingApprovals();
    }

    @PostMapping("/{approvalId}/approve")
    RefundOutcome approve(@PathVariable String approvalId, @Valid @RequestBody ApproveRequest request) {
        return refundDesk.approve(approvalId, request.approver());
    }
}
