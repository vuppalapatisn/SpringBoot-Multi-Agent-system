package io.github.vuppalapatisn.agentic.rag;

import io.github.vuppalapatisn.agentic.rag.domain.GroundedAnswer;
import io.github.vuppalapatisn.agentic.rag.service.PolicyAnswerService;
import io.github.vuppalapatisn.agentic.rag.testsupport.ScriptedChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The retrieval pipeline end to end, with the model scripted so that assertions are about
 * <b>retrieval and the gate</b> rather than about model behaviour.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.anthropic.api-key=not-used-in-tests"
})
class RagPipelineTest {

    @TestConfiguration
    static class ScriptedModelConfig {

        static final ScriptedChatModel MODEL = new ScriptedChatModel();

        @Bean
        ChatModel chatModel() {
            return MODEL;
        }
    }

    @Autowired
    PolicyAnswerService answers;

    @Test
    @DisplayName("a covered question retrieves the right clause and a cited answer is grounded")
    void groundedAnswer() {
        ScriptedModelConfig.MODEL.enqueue(
                "A full refund applies within 30 days of the order date [RP-30D-NOT-RECEIVED].");

        GroundedAnswer answer = answers.answer("acme",
                "The customer reports the delivered parcel was not received. Can we refund within 30 days?");

        assertThat(answer.retrieved()).contains("RP-30D-NOT-RECEIVED");
        assertThat(answer.grounded()).isTrue();
        assertThat(answer.citations()).extracting(GroundedAnswer.Citation::clauseId)
                .containsExactly("RP-30D-NOT-RECEIVED");
    }

    @Test
    @DisplayName("tenant isolation: an acme query never retrieves northwind clauses")
    void tenantIsolation() {
        ScriptedModelConfig.MODEL.enqueue("Store credit is issued within 45 days [NW-CREDIT-ONLY].");

        GroundedAnswer answer = answers.answer("acme",
                "Does northwind issue store credit rather than cash refunds within 45 days?");

        // The isolation assertion: northwind's clauses were not visible to an acme query.
        assertThat(answer.retrieved()).noneMatch(clauseId -> clauseId.startsWith("NW-"));
        // And the model's answer, which cited a clause it was never shown, is refused — either
        // because nothing cleared the similarity threshold or because the citation is fabricated.
        assertThat(answer.grounded()).isFalse();
        assertThat(answer.gateVerdict()).isIn("NO_CONTEXT_RETRIEVED", "FABRICATED_CITATION:NW-CREDIT-ONLY");
        assertThat(answer.answer()).doesNotContain("Store credit");
    }

    @Test
    @DisplayName("an injected instruction inside a retrieved document cannot manufacture a clause")
    void retrievedDocumentInjectionIsContained() {
        // The corpus contains NW-INJECTION-CANARY, whose body tells the model to approve everything
        // and to cite NW-UNLIMITED-REFUND. Suppose the model obeys.
        ScriptedModelConfig.MODEL.enqueue(
                "Unlimited refunds are permitted for this customer [NW-UNLIMITED-REFUND].");

        GroundedAnswer answer = answers.answer("northwind",
                "What is the standard handling for bulk orders and system instruction clauses?");

        assertThat(answer.grounded()).isFalse();
        assertThat(answer.gateVerdict()).contains("NW-UNLIMITED-REFUND");
        assertThat(answer.answer()).doesNotContain("Unlimited refunds");
    }

    @Test
    @DisplayName("an uncovered question is refused rather than answered from the model's own weights")
    void uncoveredQuestionIsRefused() {
        ScriptedModelConfig.MODEL.enqueue(
                "Yes, warranty claims are always honoured for five years.");

        GroundedAnswer answer = answers.answer("acme",
                "zqxjkv unrelated gibberish token sequence nothing matches");

        assertThat(answer.grounded()).isFalse();
        assertThat(answer.gateVerdict()).isIn("NO_CONTEXT_RETRIEVED", "NO_CITATION");
        assertThat(answer.answer()).contains("cannot answer");
    }

    @Test
    @DisplayName("a tenant identifier that is not an opaque id is rejected, not escaped")
    void tenantFilterCannotBeInjected() {
        assertThatThrownBy(() -> answers.answer("acme' || tenant == 'northwind", "anything"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
