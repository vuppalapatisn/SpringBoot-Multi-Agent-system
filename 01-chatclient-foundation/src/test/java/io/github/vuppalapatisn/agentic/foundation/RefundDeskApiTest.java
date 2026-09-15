package io.github.vuppalapatisn.agentic.foundation;

import io.github.vuppalapatisn.agentic.foundation.testsupport.ScriptedChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wiring test for the whole application context, with the provider replaced by a scripted model.
 *
 * <p>{@code spring.ai.model.chat=none} keeps the Anthropic autoconfiguration out of the way; the
 * {@link ScriptedChatModel} bean is what {@code ChatClient.Builder} is then built from.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.anthropic.api-key=not-used-in-tests"
})
@AutoConfigureMockMvc
class RefundDeskApiTest {

    @TestConfiguration
    static class ScriptedModelConfig {

        @Bean
        ChatModel chatModel() {
            return new ScriptedChatModel().enqueue(
                    """
                    {
                      "outcome": "REFUND",
                      "proposedAmountMinor": 24000,
                      "risk": "LOW",
                      "policyReference": "RP-30D-NOT-RECEIVED",
                      "rationale": "Delivered order reported as not received within 30 days."
                    }
                    """,
                    "We have reviewed your order A-1187 and a specialist will confirm the next step by email.");
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("POST /api/refunds/classify returns an advisory decision and the authoritative amount")
    void classifyIsAdvisoryOnly() throws Exception {
        mockMvc.perform(post("/api/refunds/classify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"A-1187","message":"The parcel never arrived."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision.outcome").value("REFUND"))
                .andExpect(jsonPath("$.authoritativeAmountMinor").value(24000))
                .andExpect(jsonPath("$.advisoryOnly").value(true))
                .andExpect(jsonPath("$.runId").exists());
    }

    @Test
    @DisplayName("a malformed order id is rejected at the ingress boundary, before any model call")
    void rejectsBadOrderId() throws Exception {
        mockMvc.perform(post("/api/refunds/classify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"'; DROP TABLE orders; --","message":"hello"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an unknown order returns 404 without calling the model")
    void unknownOrderIsNotFound() throws Exception {
        mockMvc.perform(post("/api/refunds/classify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"Z-9999","message":"hello"}
                                """))
                .andExpect(status().isNotFound());
    }
}
