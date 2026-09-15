package io.github.vuppalapatisn.agentic.workflow.steps;

import io.github.vuppalapatisn.agentic.workflow.domain.Domain.CaseFacts;
import io.github.vuppalapatisn.agentic.workflow.domain.Domain.FraudSignal;
import io.github.vuppalapatisn.agentic.workflow.domain.Domain.OrderSummary;
import io.github.vuppalapatisn.agentic.workflow.domain.Domain.PolicyClause;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FactGatheringTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-16T09:00:00Z"), ZoneOffset.UTC);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final FactGathering gathering = new FactGathering(executor, clock);

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    @DisplayName("the parallel stage fans out and fans in to one immutable value")
    void gathersBothBranches() {
        OrderSummary order = gathering.loadOrder("A-1187").orElseThrow();

        CaseFacts facts = gathering.gather(order);

        assertThat(facts.clauses()).extracting(PolicyClause::clauseId)
                .contains("RP-30D-NOT-RECEIVED", "RP-IN-TRANSIT");
        assertThat(facts.fraudSignal()).isEqualTo(FraudSignal.CLEAN);
        assertThat(facts.ageInDays()).isEqualTo(9);
    }

    @Test
    @DisplayName("an unknown customer degrades to UNAVAILABLE, which is not LOW risk")
    void fraudBranchDegrades() {
        OrderSummary unknownCustomer = new OrderSummary("A-9001", "c-0000",
                "zz@customers.example", 1_000L, "USD", LocalDate.now(clock).minusDays(1),
                "DELIVERED", "Mystery item");

        CaseFacts facts = gathering.gather(unknownCustomer);

        assertThat(facts.fraudSignal()).isEqualTo(FraudSignal.UNAVAILABLE);
        // The consequence that matters: an unavailable signal cannot satisfy the automatic tier.
        assertThat(facts.fraudSignal().risk()).isNotEqualTo(
                io.github.vuppalapatisn.agentic.workflow.domain.Domain.RiskLevel.LOW);
    }

    @Test
    @DisplayName("the fraud provider's free text never leaves the boundary — only an enum does")
    void fraudResultIsAnEnum() {
        assertThat(gathering.checkFraud(gathering.loadOrder("A-0988").orElseThrow()))
                .isEqualTo(FraudSignal.WATCHLIST);
        assertThat(gathering.checkFraud(gathering.loadOrder("A-1310").orElseThrow()))
                .isEqualTo(FraudSignal.VELOCITY_ABUSE);
    }

    @Test
    @DisplayName("a malformed order id is rejected, not escaped into a lookup")
    void orderIdIsValidated() {
        assertThatThrownBy(() -> gathering.loadOrder("'; DROP TABLE orders; --"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
