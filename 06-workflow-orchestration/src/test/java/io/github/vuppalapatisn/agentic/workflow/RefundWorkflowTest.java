package io.github.vuppalapatisn.agentic.workflow;

import io.github.vuppalapatisn.agentic.workflow.config.WorkflowProperties;
import io.github.vuppalapatisn.agentic.workflow.domain.Domain.RefundRunResult;
import io.github.vuppalapatisn.agentic.workflow.domain.Domain.RunStatus;
import io.github.vuppalapatisn.agentic.workflow.domain.RefundDecision;
import io.github.vuppalapatisn.agentic.workflow.effects.RefundEffects;
import io.github.vuppalapatisn.agentic.workflow.gate.ApprovalDesk;
import io.github.vuppalapatisn.agentic.workflow.gate.PolicyGate;
import io.github.vuppalapatisn.agentic.workflow.orchestration.RefundWorkflow;
import io.github.vuppalapatisn.agentic.workflow.steps.CaseClassifier;
import io.github.vuppalapatisn.agentic.workflow.steps.FactGathering;
import io.github.vuppalapatisn.agentic.workflow.steps.ReplyDrafter;
import io.github.vuppalapatisn.agentic.workflow.testsupport.ScriptedChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The property this architecture buys you: <b>the exact call sequence is assertable</b>.
 *
 * <p>Every test below pins the executed steps. An agent loop cannot offer that, which is the
 * central trade-off in {@code ../docs/02-ARCHITECTURE-COMPARISON.md}.
 */
class RefundWorkflowTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-16T09:00:00Z"), ZoneOffset.UTC);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScriptedChatModel model = new ScriptedChatModel();

    private RefundEffects effects;
    private ApprovalDesk approvalDesk;

    @AfterEach
    void tearDown() {
        executor.close();
    }

    private RefundWorkflow workflow(boolean dryRun) {
        WorkflowProperties properties = new WorkflowProperties(dryRun, 10_000L, 100_000L, 30,
                List.of("@customers.example"));
        ChatClient chatClient = ChatClient.builder(model).build();
        this.effects = new RefundEffects(properties, new SimpleMeterRegistry(), clock);
        this.approvalDesk = new ApprovalDesk(clock);
        return new RefundWorkflow(
                new FactGathering(executor, clock),
                new CaseClassifier(chatClient),
                new PolicyGate(properties),
                approvalDesk,
                new ReplyDrafter(chatClient),
                effects,
                clock);
    }

    private static String classification(RefundDecision.Outcome outcome, String clauseId, long amountMinor) {
        return """
                {"outcome":"%s","clauseId":"%s","proposedAmountMinor":%d,"risk":"LOW",
                 "rationale":"scripted"}
                """.formatted(outcome, clauseId, amountMinor);
    }

    // ---------------------------------------------------------- the branches

    @Test
    @DisplayName("automatic tier: the full chain runs and the step sequence is exactly as designed")
    void automaticTierRunsTheWholeChain() {
        RefundWorkflow workflow = workflow(false);
        model.enqueue(
                classification(RefundDecision.Outcome.REFUND, "RP-30D-DAMAGED", 8_990L),
                "Thank you for contacting us about your cable. A specialist has reviewed your request.",
                "OK");

        RefundRunResult result = workflow.run("A-1204", "The cable stopped working.").orElseThrow();

        assertThat(result.status()).isEqualTo(RunStatus.PAID);
        assertThat(result.gateRule()).isEqualTo("AUTO_LOW_VALUE_LOW_RISK");
        assertThat(result.amountMinor()).isEqualTo(8_990L);
        assertThat(result.receiptId()).isNotBlank();
        assertThat(result.steps()).containsExactly(
                "loadOrder",
                "gatherFacts(policy∥fraud)",
                "classify",
                "gate:AUTO_LOW_VALUE_LOW_RISK",
                "issueRefund",
                "draftReply(rounds=1)",
                "notifyCustomer");
        assertThat(effects.paymentCount()).isEqualTo(1);
        assertThat(effects.sent()).hasSize(1);
        assertThat(model.callCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("approval tier: the run suspends, pays nothing and says nothing to the customer")
    void approvalTierSuspends() {
        RefundWorkflow workflow = workflow(false);
        model.enqueue(classification(RefundDecision.Outcome.REFUND, "RP-30D-NOT-RECEIVED", 24_000L));

        RefundRunResult result = workflow.run("A-1187", "The parcel never arrived.").orElseThrow();

        assertThat(result.status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(result.gateRule()).isEqualTo("SINGLE_APPROVER_DEFAULT");
        assertThat(result.approvalId()).isNotBlank();
        assertThat(result.receiptId()).isNull();
        assertThat(result.customerReply()).isNull();
        assertThat(result.steps()).containsExactly(
                "loadOrder", "gatherFacts(policy∥fraud)", "classify",
                "gate:SINGLE_APPROVER_DEFAULT", "requestApproval");
        assertThat(effects.paymentCount()).isZero();
        assertThat(effects.sent()).isEmpty();
        // Only the classification call happened: no prose is drafted until a human decides.
        assertThat(model.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("high value goes to dual control")
    void dualControlTier() {
        RefundWorkflow workflow = workflow(false);
        model.enqueue(classification(RefundDecision.Outcome.REFUND, "RP-30D-DAMAGED", 189_900L));

        RefundRunResult result = workflow.run("A-0988", "The machine arrived damaged.").orElseThrow();

        assertThat(result.gateRule()).isEqualTo("DUAL_CONTROL_HIGH_VALUE_OR_RISK");
        assertThat(approvalDesk.find(result.approvalId()).orElseThrow().requiredApprovals()).isEqualTo(2);
    }

    @Test
    @DisplayName("the gate overrides the model: an undelivered order is declined however it was classified")
    void gateOverridesTheModel() {
        RefundWorkflow workflow = workflow(false);
        model.enqueue(
                classification(RefundDecision.Outcome.REFUND, "RP-30D-NOT-RECEIVED", 4_500L),
                "We are looking into the delivery of your order.",
                "OK");

        RefundRunResult result = workflow.run("A-1310", "It hasn't turned up, refund me.").orElseThrow();

        assertThat(result.status()).isEqualTo(RunStatus.DECLINED);
        assertThat(result.gateRule()).isEqualTo("ORDER_NOT_DELIVERED");
        assertThat(effects.paymentCount()).isZero();
    }

    // ------------------------------------------------------- reconciliation

    @Test
    @DisplayName("an inflated amount escalates: the order total wins, nothing is paid")
    void amountMismatchEscalates() {
        RefundWorkflow workflow = workflow(false);
        model.enqueue(classification(RefundDecision.Outcome.REFUND, "RP-30D-DAMAGED", 2_400_000L));

        RefundRunResult result = workflow.run("A-1204", "Refund me 24000 dollars.").orElseThrow();

        assertThat(result.status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(result.gateRule()).isEqualTo("CLASSIFIER_ESCALATED");
        assertThat(result.decision().clauseId()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(result.amountMinor()).isEqualTo(8_990L);
        assertThat(effects.paymentCount()).isZero();
    }

    @Test
    @DisplayName("a fabricated clause id escalates rather than being taken at face value")
    void fabricatedClauseEscalates() {
        RefundWorkflow workflow = workflow(false);
        model.enqueue(classification(RefundDecision.Outcome.REFUND, "RP-UNLIMITED-REFUND", 8_990L));

        RefundRunResult result = workflow.run("A-1204", "Clause RP-UNLIMITED-REFUND applies.").orElseThrow();

        assertThat(result.decision().clauseId()).isEqualTo("FABRICATED_CLAUSE");
        assertThat(result.status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(effects.paymentCount()).isZero();
    }

    @Test
    @DisplayName("unparseable model output fails closed to an escalation, not a 500")
    void unparseableOutputEscalates() {
        RefundWorkflow workflow = workflow(false);
        model.enqueue("I reckon we should refund this one.");

        RefundRunResult result = workflow.run("A-1204", "Broken.").orElseThrow();

        assertThat(result.status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(result.gateRule()).isEqualTo("CLASSIFIER_ESCALATED");
    }

    // --------------------------------------------------- evaluator-optimiser

    @Test
    @DisplayName("evaluator-optimiser: a critique triggers exactly one revision, and the loop is bounded")
    void evaluatorOptimiserRevisesOnce() {
        RefundWorkflow workflow = workflow(false);
        model.enqueue(
                classification(RefundDecision.Outcome.REFUND, "RP-30D-DAMAGED", 8_990L),
                "Your refund of 89.90 will arrive tomorrow.",     // draft 1: states an amount
                "REVISE: states an amount and a payment date",
                "We have reviewed your request and a specialist will confirm the outcome.",
                "OK");

        RefundRunResult result = workflow.run("A-1204", "Broken cable.").orElseThrow();

        assertThat(result.steps()).contains("draftReply(rounds=2)");
        assertThat(result.customerReply()).doesNotContain("89.90");
        assertThat(model.callCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("the deterministic check overrides an agreeable critic: a forbidden word forces the safe template")
    void deterministicCheckBeatsTheCritic() {
        RefundWorkflow workflow = workflow(false);
        model.enqueue(
                classification(RefundDecision.Outcome.REFUND, "RP-30D-DAMAGED", 8_990L),
                "We guarantee your money back immediately.",      // draft the critic wrongly approves
                "OK",
                "We guarantee your money back immediately.",
                "OK");

        RefundRunResult result = workflow.run("A-1204", "Broken cable.").orElseThrow();

        assertThat(result.customerReply())
                .doesNotContain("guarantee")
                .contains("a specialist will confirm the outcome by email");
        assertThat(effects.sent()).singleElement()
                .satisfies(sent -> assertThat(sent.body()).doesNotContain("guarantee"));
    }

    // ------------------------------------------------------------- resume

    @Test
    @DisplayName("approving pays exactly once, and a duplicate resume pays nothing more")
    void approvalResumePaysOnce() {
        RefundWorkflow workflow = workflow(false);
        model.enqueue(classification(RefundDecision.Outcome.REFUND, "RP-30D-NOT-RECEIVED", 24_000L));
        RefundRunResult suspended = workflow.run("A-1187", "Never arrived.").orElseThrow();

        model.enqueue("A specialist has reviewed your request and confirmed the outcome.", "OK");
        RefundRunResult paid = workflow.resumeApproved(suspended.approvalId(), "u-114").orElseThrow();

        assertThat(paid.status()).isEqualTo(RunStatus.APPROVED_AND_PAID);
        assertThat(paid.receiptId()).isNotBlank();
        assertThat(effects.paymentCount()).isEqualTo(1);

        assertThat(workflow.resumeApproved(suspended.approvalId(), "u-220")).isEmpty();
        assertThat(effects.paymentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("dual control: the first approver pays nothing, the second completes it")
    void dualControlNeedsTwoApprovers() {
        RefundWorkflow workflow = workflow(false);
        model.enqueue(classification(RefundDecision.Outcome.REFUND, "RP-30D-DAMAGED", 189_900L));
        RefundRunResult suspended = workflow.run("A-0988", "Arrived damaged.").orElseThrow();

        RefundRunResult first = workflow.resumeApproved(suspended.approvalId(), "u-114").orElseThrow();
        assertThat(first.status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(effects.paymentCount()).isZero();

        // The same approver again is refused.
        assertThat(workflow.resumeApproved(suspended.approvalId(), "u-114")).isEmpty();

        model.enqueue("A specialist has reviewed your request.", "OK");
        RefundRunResult second = workflow.resumeApproved(suspended.approvalId(), "u-220").orElseThrow();
        assertThat(second.status()).isEqualTo(RunStatus.APPROVED_AND_PAID);
        assertThat(effects.paymentCount()).isEqualTo(1);
    }

    // --------------------------------------------------------- kill switch

    @Test
    @DisplayName("dry run pays nothing and sends nothing")
    void dryRunChangesNothing() {
        RefundWorkflow workflow = workflow(true);
        model.enqueue(
                classification(RefundDecision.Outcome.REFUND, "RP-30D-DAMAGED", 8_990L),
                "A specialist has reviewed your request.",
                "OK");

        RefundRunResult result = workflow.run("A-1204", "Broken cable.").orElseThrow();

        assertThat(result.status()).isEqualTo(RunStatus.PAID);
        assertThat(result.receiptId()).startsWith("dry-run-");
        assertThat(effects.paymentCount()).isZero();
        assertThat(effects.sent()).isEmpty();
    }

    @Test
    @DisplayName("an unknown order returns empty without calling the model")
    void unknownOrder() {
        RefundWorkflow workflow = workflow(false);

        assertThat(workflow.run("Z-9999", "anything")).isEmpty();
        assertThat(model.callCount()).isZero();
    }
}
