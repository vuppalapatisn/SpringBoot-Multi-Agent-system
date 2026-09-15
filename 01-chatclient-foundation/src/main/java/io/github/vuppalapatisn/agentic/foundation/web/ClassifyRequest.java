package io.github.vuppalapatisn.agentic.foundation.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The {@code [IN]} node of the control-flow graph. Validation here is the first trust boundary:
 * bounded length, constrained order-id shape, nothing free-form that reaches a query or a path.
 */
public record ClassifyRequest(

        @NotBlank
        @Pattern(regexp = "[A-Z]-\\d{4}", message = "orderId must look like A-1187")
        String orderId,

        @NotBlank
        @Size(max = 8_000, message = "message too long")
        String message,

        @Size(max = 64)
        String conversationId) {
}
