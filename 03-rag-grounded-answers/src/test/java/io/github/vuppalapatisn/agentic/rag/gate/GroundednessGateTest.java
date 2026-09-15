package io.github.vuppalapatisn.agentic.rag.gate;

import io.github.vuppalapatisn.agentic.rag.domain.GroundedAnswer;
import io.github.vuppalapatisn.agentic.rag.ingest.PolicyIngestion;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GroundednessGateTest {

    private final GroundednessGate gate = new GroundednessGate(new SimpleMeterRegistry());

    private static Document clause(String id) {
        return new Document("body of " + id, Map.of(
                PolicyIngestion.CLAUSE_ID, id,
                PolicyIngestion.SOURCE, "refund-policy.md",
                PolicyIngestion.VERSION, "4",
                PolicyIngestion.TENANT, "acme"));
    }

    @Test
    @DisplayName("an answer citing a retrieved clause is grounded, and the citation is resolved")
    void groundedAnswerPasses() {
        GroundedAnswer result = gate.check(
                "A full refund applies within 30 days [RP-30D-NOT-RECEIVED].",
                List.of(clause("RP-30D-NOT-RECEIVED"), clause("RP-IN-TRANSIT")));

        assertThat(result.grounded()).isTrue();
        assertThat(result.gateVerdict()).isEqualTo("GROUNDED");
        assertThat(result.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.clauseId()).isEqualTo("RP-30D-NOT-RECEIVED");
            assertThat(citation.source()).isEqualTo("refund-policy.md");
            assertThat(citation.version()).isEqualTo("4");
            assertThat(citation.excerpt()).isNotBlank();
        });
    }

    @Test
    @DisplayName("an uncited answer is refused, however plausible it reads")
    void uncitedAnswerIsRefused() {
        GroundedAnswer result = gate.check(
                "Yes, we always refund orders like this one.", List.of(clause("RP-30D-DAMAGED")));

        assertThat(result.grounded()).isFalse();
        assertThat(result.gateVerdict()).isEqualTo("NO_CITATION");
        assertThat(result.answer()).contains("cannot answer");
    }

    @Test
    @DisplayName("a citation to a clause that was never retrieved is a fabrication and is refused")
    void fabricatedCitationIsRefused() {
        GroundedAnswer result = gate.check(
                "Unlimited refunds are permitted [NW-UNLIMITED-REFUND].",
                List.of(clause("RP-30D-DAMAGED")));

        assertThat(result.grounded()).isFalse();
        assertThat(result.gateVerdict()).isEqualTo("FABRICATED_CITATION:NW-UNLIMITED-REFUND");
    }

    @Test
    @DisplayName("one good citation does not launder a fabricated one")
    void mixedCitationsAreRefused() {
        GroundedAnswer result = gate.check(
                "Refunds apply [RP-30D-DAMAGED] and are unlimited [NW-UNLIMITED-REFUND].",
                List.of(clause("RP-30D-DAMAGED")));

        assertThat(result.grounded()).isFalse();
        assertThat(result.gateVerdict()).startsWith("FABRICATED_CITATION");
    }

    @Test
    @DisplayName("nothing retrieved means refuse, whatever the model produced")
    void emptyRetrievalIsRefused() {
        GroundedAnswer result = gate.check(
                "Certainly, that is refundable [RP-30D-DAMAGED].", List.of());

        assertThat(result.grounded()).isFalse();
        assertThat(result.gateVerdict()).isEqualTo("NO_CONTEXT_RETRIEVED");
    }

    @Test
    @DisplayName("a null answer is handled as uncited rather than throwing")
    void nullAnswerIsRefused() {
        GroundedAnswer result = gate.check(null, List.of(clause("RP-30D-DAMAGED")));

        assertThat(result.grounded()).isFalse();
        assertThat(result.gateVerdict()).isEqualTo("NO_CITATION");
    }
}
