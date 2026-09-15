package io.github.vuppalapatisn.agentic.rag.gate;

import io.github.vuppalapatisn.agentic.rag.domain.GroundedAnswer;
import io.github.vuppalapatisn.agentic.rag.ingest.PolicyIngestion;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deterministic groundedness gate.
 *
 * <p>RAG systems fail in a specific way: retrieval returns nothing useful and the model answers
 * from its own weights anyway, fluently and wrongly. Asking the model to "only use the context"
 * reduces that; it does not prevent it. This gate <b>checks</b>.
 *
 * <p>Three rules, all mechanical:
 *
 * <ol>
 *   <li><b>At least one citation.</b> An uncited answer about policy is refused.</li>
 *   <li><b>Every cited clause was actually retrieved.</b> A citation to a clause that retrieval
 *       never returned is a fabrication, and fabricating a plausible clause id is exactly what a
 *       language model is good at.</li>
 *   <li><b>Nothing to cite means refuse.</b> If retrieval was empty, the answer is replaced with a
 *       refusal regardless of what the model said.</li>
 * </ol>
 *
 * <p>Deliberately <b>not</b> an LLM judge. A judge is appropriate for measuring tone; a citation
 * either exists in the retrieved set or it does not, and that is a set membership test.
 */
@Component
public class GroundednessGate {

    /** Citations are emitted as [CLAUSE-ID] by the system prompt. */
    private static final Pattern CITATION = Pattern.compile("\\[([A-Z][A-Z0-9-]{2,40})]");

    private final MeterRegistry meters;

    public GroundednessGate(MeterRegistry meters) {
        this.meters = meters;
    }

    public GroundedAnswer check(String answer, List<Document> retrieved) {
        List<String> retrievedIds = retrieved.stream()
                .map(document -> String.valueOf(document.getMetadata().get(PolicyIngestion.CLAUSE_ID)))
                .toList();

        if (retrieved.isEmpty()) {
            return verdict(GroundedAnswer.refused("NO_CONTEXT_RETRIEVED", retrievedIds));
        }

        Set<String> cited = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            cited.add(matcher.group(1));
        }

        if (cited.isEmpty()) {
            return verdict(GroundedAnswer.refused("NO_CITATION", retrievedIds));
        }

        List<String> fabricated = cited.stream().filter(id -> !retrievedIds.contains(id)).toList();
        if (!fabricated.isEmpty()) {
            return verdict(GroundedAnswer.refused("FABRICATED_CITATION:" + String.join(",", fabricated),
                    retrievedIds));
        }

        List<GroundedAnswer.Citation> citations = new ArrayList<>();
        for (Document document : retrieved) {
            String clauseId = String.valueOf(document.getMetadata().get(PolicyIngestion.CLAUSE_ID));
            if (cited.contains(clauseId)) {
                citations.add(new GroundedAnswer.Citation(
                        clauseId,
                        String.valueOf(document.getMetadata().get(PolicyIngestion.SOURCE)),
                        String.valueOf(document.getMetadata().get(PolicyIngestion.VERSION)),
                        excerpt(document.getText())));
            }
        }
        return verdict(new GroundedAnswer(answer, citations, retrievedIds, true, "GROUNDED"));
    }

    private GroundedAnswer verdict(GroundedAnswer answer) {
        meters.counter("agentic.rag.groundedness",
                "verdict", answer.gateVerdict().split(":")[0]).increment();
        return answer;
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String flattened = text.replaceAll("\\s+", " ").trim();
        return flattened.length() <= 180 ? flattened : flattened.substring(0, 180) + "…";
    }
}
