package io.github.vuppalapatisn.agentic.multiagent.authority;

import io.github.vuppalapatisn.agentic.multiagent.authority.AgentCapabilities.Capability;
import io.github.vuppalapatisn.agentic.multiagent.authority.AgentRole.BoundaryClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Least authority as a property of the running system rather than a principle in a document.
 *
 * <p>These are the tests that make this project's security model real: they fail the build if a
 * future change hands a payment capability to an agent that reads attacker-controlled text.
 */
class AgentAuthorityTest {

    @Test
    @DisplayName("only the payout role may hold an effectful capability")
    void onlyPayoutMayCauseEffects() {
        assertThat(AgentRole.INTAKE.mayCauseEffects()).isFalse();
        assertThat(AgentRole.POLICY.mayCauseEffects()).isFalse();
        assertThat(AgentRole.FRAUD.mayCauseEffects()).isFalse();
        assertThat(AgentRole.PAYOUT.mayCauseEffects()).isTrue();
    }

    @Test
    @DisplayName("the intake agent, which reads untrusted text, holds nothing at all")
    void intakeHoldsNothing() {
        assertThat(AgentRole.INTAKE.allowedClasses()).isEmpty();
    }

    @Test
    @DisplayName("only the agent that can act may ask a human to authorise acting")
    void onlyPayoutMayEscalate() {
        assertThat(AgentRole.PAYOUT.mayEscalateToHuman()).isTrue();
        assertThat(AgentRole.FRAUD.mayEscalateToHuman()).isFalse();
    }

    @Test
    @DisplayName("handing a payment capability to the fraud agent fails at startup")
    void capabilityOutsideTheRoleIsRejected() {
        assertThatThrownBy(() -> new AgentCapabilities(Map.of(
                AgentRole.FRAUD, List.of(new Capability("issueRefund", BoundaryClass.E2)))))
                .isInstanceOf(AgentCapabilities.AuthorityViolationException.class)
                .hasMessageContaining("issueRefund")
                .hasMessageContaining("FRAUD");
    }

    @Test
    @DisplayName("handing the intake agent even a read capability fails: it is supposed to hold nothing")
    void intakeCannotHoldAnything() {
        assertThatThrownBy(() -> new AgentCapabilities(Map.of(
                AgentRole.INTAKE, List.of(new Capability("lookupOrder", BoundaryClass.R0)))))
                .isInstanceOf(AgentCapabilities.AuthorityViolationException.class);
    }

    @Test
    @DisplayName("exactly one role may cause effects, so the second-effectful-role guard is unreachable today")
    void onlyOneRoleCanEverCauseEffects() {
        // The per-role check fires first for any attempt to give a second role an effectful tool,
        // so AgentCapabilities' "more than one effectful role" guard cannot trigger with the
        // current AgentRole definitions. It is deliberate defence for the change that adds a fifth
        // role — the moment somebody does, this assertion is what tells them to think about it.
        assertThat(java.util.Arrays.stream(AgentRole.values())
                .filter(AgentRole::mayCauseEffects)
                .toList())
                .containsExactly(AgentRole.PAYOUT);
    }

    @Test
    @DisplayName("the real register is valid and has exactly one effectful role")
    void theShippedRegisterIsValid() {
        AgentCapabilities capabilities = new AgentCapabilities(Map.of(
                AgentRole.INTAKE, List.of(),
                AgentRole.POLICY, List.of(new Capability("lookupPolicy", BoundaryClass.R0)),
                AgentRole.FRAUD, List.of(
                        new Capability("lookupOrder", BoundaryClass.R0),
                        new Capability("checkFraudSignal", BoundaryClass.R1)),
                AgentRole.PAYOUT, List.of(
                        new Capability("lookupOrder", BoundaryClass.R0),
                        new Capability("issueRefund", BoundaryClass.E2),
                        new Capability("notifyCustomer", BoundaryClass.E2))));

        assertThat(capabilities.effectfulRoles()).containsExactly(AgentRole.PAYOUT);
        assertThat(capabilities.of(AgentRole.INTAKE)).isEmpty();
        assertThat(capabilities.of(AgentRole.FRAUD))
                .extracting(Capability::boundaryClass)
                .containsExactly(BoundaryClass.R0, BoundaryClass.R1);
        assertThat(capabilities.describe()).contains("PAYOUT", "issueRefund", "E2");
    }
}
