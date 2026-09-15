package io.github.vuppalapatisn.agentic.rag.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ingestion for a policy corpus.
 *
 * <p><b>Chunk on the document's own structure, not on a token count.</b> A policy is a set of
 * clauses, and a clause is the unit a human cites, so each clause becomes one document with its id
 * in the metadata. Splitting a policy every 512 tokens produces chunks that straddle two rules,
 * which is how a retrieval system ends up citing a clause that does not say what the answer claims.
 *
 * <p>Metadata carried per chunk, and why each field earns its place:
 *
 * <ul>
 *   <li>{@code clauseId} — what the model must cite, and what the groundedness gate verifies</li>
 *   <li>{@code tenant} — the filter that keeps one customer's documents out of another's answers</li>
 *   <li>{@code version}, {@code source} — so an answer can be traced to a document revision</li>
 * </ul>
 *
 * <p>Corpus format ({@code src/main/resources/policies/*.md}): {@code ## CLAUSE-ID | tenant | version}
 * starts a clause; the lines beneath it are the clause body.
 */
@Component
public class PolicyIngestion {

    private static final Logger log = LoggerFactory.getLogger(PolicyIngestion.class);

    public static final String CLAUSE_ID = "clauseId";
    public static final String TENANT = "tenant";
    public static final String VERSION = "version";
    public static final String SOURCE = "source";

    private final VectorStore vectorStore;

    public PolicyIngestion(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /** @return the documents that were indexed */
    public List<Document> ingest(String locationPattern) {
        List<Document> documents = read(locationPattern);
        if (documents.isEmpty()) {
            throw new IllegalStateException("no policy clauses found at " + locationPattern);
        }
        vectorStore.add(documents);
        log.info("indexed {} policy clauses from {}", documents.size(), locationPattern);
        return documents;
    }

    List<Document> read(String locationPattern) {
        List<Document> documents = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(locationPattern);
            for (Resource resource : resources) {
                documents.addAll(parse(resource));
            }
        }
        catch (IOException ex) {
            throw new UncheckedIOException("cannot read policy corpus " + locationPattern, ex);
        }
        return documents;
    }

    private List<Document> parse(Resource resource) throws IOException {
        String text = resource.getContentAsString(StandardCharsets.UTF_8);
        String filename = resource.getFilename() == null ? "unknown" : resource.getFilename();

        List<Document> clauses = new ArrayList<>();
        String clauseId = null;
        String tenant = null;
        String version = null;
        StringBuilder body = new StringBuilder();

        for (String line : text.split("\\R")) {
            if (line.startsWith("## ")) {
                flush(clauses, clauseId, tenant, version, filename, body);
                String[] parts = line.substring(3).split("\\|");
                clauseId = parts[0].trim();
                tenant = parts.length > 1 ? parts[1].trim() : "default";
                version = parts.length > 2 ? parts[2].trim() : "1";
                body.setLength(0);
            }
            else if (clauseId != null) {
                body.append(line).append('\n');
            }
        }
        flush(clauses, clauseId, tenant, version, filename, body);
        return clauses;
    }

    private void flush(List<Document> clauses, String clauseId, String tenant, String version,
                       String filename, StringBuilder body) {
        if (clauseId == null || body.toString().isBlank()) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(CLAUSE_ID, clauseId);
        metadata.put(TENANT, tenant);
        metadata.put(VERSION, version);
        metadata.put(SOURCE, filename);
        // The clause id is part of the embedded text as well as the metadata: a question that
        // mentions a clause by name should retrieve it.
        clauses.add(new Document(clauseId + "\n" + body.toString().trim(), metadata));
    }
}
