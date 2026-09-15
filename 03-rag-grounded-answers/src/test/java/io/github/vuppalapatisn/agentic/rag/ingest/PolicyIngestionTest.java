package io.github.vuppalapatisn.agentic.rag.ingest;

import io.github.vuppalapatisn.agentic.rag.embedding.HashingEmbeddingModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyIngestionTest {

    private final VectorStore vectorStore =
            SimpleVectorStore.builder(new HashingEmbeddingModel(512)).build();
    private final PolicyIngestion ingestion = new PolicyIngestion(vectorStore);

    @Test
    @DisplayName("each policy clause becomes one document carrying its id, tenant, version and source")
    void clausesBecomeDocumentsWithMetadata() {
        List<Document> documents = ingestion.read("classpath*:/policies/*.md");

        assertThat(documents).hasSizeGreaterThanOrEqualTo(9);
        assertThat(documents).anySatisfy(document -> {
            assertThat(document.getMetadata().get(PolicyIngestion.CLAUSE_ID))
                    .isEqualTo("RP-30D-NOT-RECEIVED");
            assertThat(document.getMetadata().get(PolicyIngestion.TENANT)).isEqualTo("acme");
            assertThat(document.getMetadata().get(PolicyIngestion.VERSION)).isEqualTo("4");
            assertThat(document.getMetadata().get(PolicyIngestion.SOURCE)).isEqualTo("refund-policy.md");
            assertThat(document.getText()).contains("not received", "30 days");
        });
        assertThat(documents)
                .extracting(document -> document.getMetadata().get(PolicyIngestion.TENANT))
                .contains("acme", "northwind");
    }

    @Test
    @DisplayName("every clause has a non-blank id and body — a silently empty chunk is a retrieval hole")
    void noEmptyChunks() {
        List<Document> documents = ingestion.read("classpath*:/policies/*.md");

        assertThat(documents).allSatisfy(document -> {
            assertThat(String.valueOf(document.getMetadata().get(PolicyIngestion.CLAUSE_ID))).isNotBlank();
            assertThat(document.getText()).isNotBlank();
        });
    }

    @Test
    @DisplayName("indexed clauses are retrievable by their own terms")
    void indexedClausesAreRetrievable() {
        ingestion.ingest("classpath*:/policies/*.md");

        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query("order damaged faulty within 30 days delivered refund")
                .topK(3)
                .similarityThreshold(0.1)
                .build());

        assertThat(hits).isNotNull();
        assertThat(hits)
                .extracting(document -> document.getMetadata().get(PolicyIngestion.CLAUSE_ID))
                .contains("RP-30D-DAMAGED");
    }
}
