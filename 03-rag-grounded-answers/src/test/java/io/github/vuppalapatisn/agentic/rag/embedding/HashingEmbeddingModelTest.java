package io.github.vuppalapatisn.agentic.rag.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HashingEmbeddingModelTest {

    private final HashingEmbeddingModel model = new HashingEmbeddingModel(512);

    @Test
    @DisplayName("the same text always embeds to the same vector — retrieval assertions stay stable")
    void deterministic() {
        assertThat(model.embed("refund within 30 days")).isEqualTo(model.embed("refund within 30 days"));
        assertThat(model.dimensions()).isEqualTo(512);
    }

    @Test
    @DisplayName("vectors are L2-normalised, so cosine similarity is a dot product")
    void normalised() {
        float[] vector = model.embed("delivered order reported as damaged or faulty");

        double norm = 0.0;
        for (float value : vector) {
            norm += value * value;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    @DisplayName("term overlap drives similarity — and the absence of semantics is why this is not production")
    void similarityFollowsTermOverlap() {
        float[] query = model.embed("order damaged faulty refund");
        double overlapping = dot(query, model.embed("a damaged or faulty order may be refunded"));
        double unrelated = dot(query, model.embed("store credit is issued for bulk purchases"));

        assertThat(overlapping).isGreaterThan(unrelated);

        // The honest limitation, asserted: a paraphrase with no shared terms scores zero.
        assertThat(dot(model.embed("cannot be delivered"), model.embed("undeliverable")))
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("empty input embeds to a zero vector rather than throwing")
    void emptyInput() {
        assertThat(dot(model.embed(""), model.embed("anything"))).isZero();
    }

    @Test
    @DisplayName("an absurdly small dimension count is rejected at construction")
    void rejectsTinyDimensions() {
        assertThatThrownBy(() -> new HashingEmbeddingModel(8))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
