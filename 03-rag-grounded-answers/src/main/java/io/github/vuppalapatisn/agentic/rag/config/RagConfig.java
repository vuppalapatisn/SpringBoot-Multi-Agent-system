package io.github.vuppalapatisn.agentic.rag.config;

import io.github.vuppalapatisn.agentic.rag.embedding.HashingEmbeddingModel;
import io.github.vuppalapatisn.agentic.rag.ingest.PolicyIngestion;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Configuration(proxyBeanMethods = false)
public class RagConfig {

    /**
     * Offline embeddings. Replace with {@code spring-ai-starter-model-transformers} (local ONNX) or
     * a hosted embedding model for anything real — see {@link HashingEmbeddingModel}. Nothing else
     * in this project knows which model is in use.
     */
    @Bean
    EmbeddingModel embeddingModel(RagProperties properties) {
        return new HashingEmbeddingModel(properties.embeddingDimensions());
    }

    /**
     * An in-memory store, so the project runs with no infrastructure. For production swap in
     * {@code spring-ai-starter-vector-store-pgvector} and configure
     * {@code spring.ai.vectorstore.pgvector.*}; the rest of this project is unchanged, which is the
     * benefit of coding against {@link VectorStore}.
     */
    @Bean
    VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    @Bean
    ApplicationRunner indexPolicyCorpus(PolicyIngestion ingestion, RagProperties properties) {
        return args -> ingestion.ingest(properties.corpusLocation());
    }

    /**
     * The modular RAG pipeline: retrieve → augment → generate.
     *
     * <p>{@code allowEmptyContext(false)} is the load-bearing setting. With it, a question the
     * corpus does not cover produces a refusal instead of an answer from the model's own weights.
     * The {@link io.github.vuppalapatisn.agentic.rag.gate.GroundednessGate} then verifies the
     * result, because a prompt instruction is a request and a gate is a check.
     */
    @Bean
    RetrievalAugmentationAdvisor retrievalAdvisor(VectorStore vectorStore, RagProperties properties) {
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .vectorStore(vectorStore)
                        .topK(properties.topK())
                        .similarityThreshold(properties.similarityThreshold())
                        .build())
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(false)
                        .promptTemplate(PromptTemplate.builder()
                                .resource(new ClassPathResource("prompts/rag-augment.st"))
                                .build())
                        .build())
                .build();
    }

    @Bean
    ChatClient policyChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(new ClassPathResource("prompts/rag-system.st"))
                .defaultOptions(ChatOptions.builder().temperature(0.0d).maxTokens(700))
                .build();
    }
}
