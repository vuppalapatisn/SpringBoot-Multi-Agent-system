package io.github.vuppalapatisn.agentic.rag.embedding;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A deterministic, offline embedding model: hashed bag-of-terms with sublinear term weighting and
 * L2 normalisation.
 *
 * <p><b>Why this exists.</b> Every test in this repository must run with no API key and no network,
 * and the build must not pull a 90&nbsp;MB ONNX model. For a small, term-rich corpus like refund
 * policy clauses this retrieves well enough to demonstrate the pipeline honestly, and it makes
 * retrieval assertions deterministic — the same query returns the same clauses on every machine,
 * forever.
 *
 * <p><b>What it is not.</b> It has no semantic understanding: "cannot be delivered" and
 * "undeliverable" share no terms and therefore no similarity. Do not ship it.
 *
 * <p><b>Swapping it out</b> is a two-line change, because nothing else in the project knows which
 * embedding model is in use:
 *
 * <pre>{@code
 * // pom.xml
 * <dependency>
 *   <groupId>org.springframework.ai</groupId>
 *   <artifactId>spring-ai-starter-model-transformers</artifactId>   <!-- local ONNX -->
 * </dependency>
 * // then delete the HashingEmbeddingModel bean; autoconfiguration supplies EmbeddingModel
 * }</pre>
 *
 * Embeddings are a versioned artefact: changing the model invalidates the whole index, so treat it
 * as a migration and record the model name in the vector store's metadata.
 */
public class HashingEmbeddingModel implements EmbeddingModel {

    public static final String MODEL_NAME = "hashing-bow-v1";

    private final int dimensions;

    public HashingEmbeddingModel(int dimensions) {
        if (dimensions < 32) {
            throw new IllegalArgumentException("dimensions must be at least 32");
        }
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        List<String> instructions = request.getInstructions();
        for (int index = 0; index < instructions.size(); index++) {
            embeddings.add(new Embedding(embed(instructions.get(index)), index));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText() == null ? "" : document.getText());
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimensions];
        if (text == null || text.isBlank()) {
            return vector;
        }
        for (String term : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (term.length() < 3) {
                continue;                       // drop noise words cheaply
            }
            int bucket = Math.abs(term.hashCode() % dimensions);
            vector[bucket] += 1.0f;
        }
        // Sublinear term weighting, then L2 normalisation so cosine similarity is a dot product.
        double norm = 0.0;
        for (int i = 0; i < dimensions; i++) {
            if (vector[i] > 0) {
                vector[i] = (float) (1.0 + Math.log(vector[i]));
                norm += vector[i] * vector[i];
            }
        }
        if (norm > 0) {
            float inverse = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < dimensions; i++) {
                vector[i] *= inverse;
            }
        }
        return vector;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }
}
