package io.github.vuppalapatisn.agentic.rag.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param corpusLocation      where the policy clauses live
 * @param embeddingDimensions size of the offline embedding vector
 * @param topK                how many clauses reach the context window
 * @param similarityThreshold below this, a clause is not relevant enough to cite. Raising it trades
 *                            recall for groundedness — an empty result is a refusal, which is the
 *                            correct outcome for a question the policy does not cover.
 * @param maxQuestionChars    bound on untrusted input entering the context
 */
@Validated
@ConfigurationProperties(prefix = "agentic.rag")
public record RagProperties(

        @NotBlank String corpusLocation,

        @Min(64) int embeddingDimensions,

        @Min(1) int topK,

        @DecimalMin("0.0") @DecimalMax("1.0") double similarityThreshold,

        @Min(50) int maxQuestionChars) {
}
