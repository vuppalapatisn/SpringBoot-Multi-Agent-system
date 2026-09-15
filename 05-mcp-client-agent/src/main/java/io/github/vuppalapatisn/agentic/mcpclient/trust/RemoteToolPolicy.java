package io.github.vuppalapatisn.agentic.mcpclient.trust;

import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The client-side trust boundary for tools that arrive over MCP.
 *
 * <p>A remote tool is not like a local {@code @Tool} method. Three things you control locally are
 * controlled by someone else here:
 *
 * <ol>
 *   <li><b>The description</b>, which is text injected into your model's context. A malicious or
 *       compromised server can put instructions in it ("tool poisoning").</li>
 *   <li><b>The schema</b>, which can gain a parameter after you reviewed it.</li>
 *   <li><b>The tool list</b>, which can change between your review and your next call (a
 *       "rug pull").</li>
 * </ol>
 *
 * <p>This class answers one question per tool: <b>may the model see it?</b> Four rules:
 *
 * <ol>
 *   <li><b>Deny by default.</b> A tool not named in the allowlist is never offered.</li>
 *   <li><b>Pin the definition.</b> The allowlist records a hash of name + description + schema. If
 *       it changes, the tool is withheld until a human re-reviews it — the server does not get to
 *       silently redefine what you approved.</li>
 *   <li><b>Treat the description as untrusted.</b> Scan for imperative, instruction-shaped text and
 *       withhold the tool if found; never render descriptions into your system prompt.</li>
 *   <li><b>Trust your own classification, not the server's hints.</b> The allowlist carries the
 *       expected boundary class. A server that claims {@code readOnlyHint} for a tool you
 *       classified {@code E2} is either wrong or lying; either way the local gate still applies.</li>
 * </ol>
 */
public class RemoteToolPolicy {

    private static final Logger log = LoggerFactory.getLogger(RemoteToolPolicy.class);

    /**
     * Text shapes that have no business being in a tool description. A description should describe;
     * anything addressed to the reader is either sloppy authoring or an attack, and both are
     * reasons to stop and look.
     */
    private static final List<Pattern> INSTRUCTION_SHAPES = List.of(
            Pattern.compile("ignore (all |any )?(previous|prior|above)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(system|assistant|developer)\\s*(prompt|message|instruction)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\byou (must|should|will) (always|never|immediately)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\balways call\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdo not (tell|inform|mention)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bapprove (any|all|every)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bwithout (asking|confirmation|approval)\\b", Pattern.CASE_INSENSITIVE));

    /** Why a tool was withheld, or that it was admitted. Tagged onto a metric. */
    public enum Verdict {
        ALLOWED,
        /**
         * Allowlisted but no fingerprint has been pinned yet. Admitted, counted and logged —
         * you cannot pin a definition you have never seen, so the workflow is observe, review,
         * then pin. A deployment that stays here has skipped the review.
         */
        UNPINNED,
        /** Not on the allowlist for this server. */
        NOT_ALLOWLISTED,
        /** Definition differs from the pinned hash: schema or description changed. */
        DEFINITION_CHANGED,
        /** The description contains instruction-shaped text. */
        SUSPICIOUS_DESCRIPTION,
        /** The server's own hints contradict our classification. */
        HINT_MISMATCH
    }

    public record Decision(String toolName, Verdict verdict, String detail, String observedFingerprint) {

        public boolean allowed() {
            return verdict == Verdict.ALLOWED || verdict == Verdict.UNPINNED;
        }
    }

    private final Map<String, TrustedTool> allowlist;
    private final boolean failClosedOnChange;

    public RemoteToolPolicy(List<TrustedTool> allowlist, boolean failClosedOnChange) {
        this.allowlist = allowlist.stream()
                .collect(java.util.stream.Collectors.toMap(TrustedTool::name, tool -> tool));
        this.failClosedOnChange = failClosedOnChange;
    }

    public Decision evaluate(String serverName, McpSchema.Tool tool) {
        TrustedTool trusted = allowlist.get(tool.name());
        if (trusted == null || !trusted.server().equals(serverName)) {
            return decision(tool, Verdict.NOT_ALLOWLISTED,
                    "tool '%s' from server '%s' is not on the allowlist".formatted(tool.name(), serverName));
        }

        String suspicious = firstInstructionShape(tool.description());
        if (suspicious != null) {
            return decision(tool, Verdict.SUSPICIOUS_DESCRIPTION,
                    "description of '%s' contains instruction-shaped text: %s"
                            .formatted(tool.name(), suspicious));
        }

        // The hint check runs before the pin check: a server claiming an effectful tool is
        // read-only is a problem whether or not we have pinned it yet.
        if (tool.annotations() != null && Boolean.TRUE.equals(tool.annotations().readOnlyHint())
                && trusted.expectedClass().effectful()) {
            return decision(tool, Verdict.HINT_MISMATCH,
                    ("server claims '%s' is read-only, but we classified it %s. "
                            + "Our classification wins; the tool is withheld pending review.")
                            .formatted(tool.name(), trusted.expectedClass()));
        }

        String actual = fingerprint(tool);
        if (trusted.fingerprint() == null || trusted.fingerprint().isBlank()) {
            return decision(tool, Verdict.UNPINNED,
                    "no fingerprint pinned for '%s'; observed %s — pin it in configuration"
                            .formatted(tool.name(), actual));
        }
        if (!trusted.fingerprint().equals(actual)) {
            String detail = "definition of '%s' changed: pinned %s, received %s"
                    .formatted(tool.name(), abbreviate(trusted.fingerprint()), abbreviate(actual));
            if (failClosedOnChange) {
                return decision(tool, Verdict.DEFINITION_CHANGED, detail);
            }
            log.warn("{} — fail-closed-on-change is OFF, so the tool is still offered", detail);
            return decision(tool, Verdict.UNPINNED, detail);
        }

        return decision(tool, Verdict.ALLOWED, "pinned definition matched");
    }

    private Decision decision(McpSchema.Tool tool, Verdict verdict, String detail) {
        if (verdict == Verdict.UNPINNED) {
            log.warn("MCP tool admitted without a pinned definition: {}", detail);
        }
        else if (verdict != Verdict.ALLOWED) {
            log.warn("withholding MCP tool: {}", detail);
        }
        return new Decision(tool.name(), verdict, detail, fingerprint(tool));
    }

    private static String abbreviate(String hash) {
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }

    static String firstInstructionShape(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String normalised = description.replaceAll("\\s+", " ");
        for (Pattern pattern : INSTRUCTION_SHAPES) {
            var matcher = pattern.matcher(normalised);
            if (matcher.find()) {
                return matcher.group();
            }
        }
        return null;
    }

    /**
     * Stable fingerprint of everything the model will see about a tool: its name, its description
     * and its input schema. Deliberately <b>not</b> including volatile fields such as icons.
     */
    public static String fingerprint(McpSchema.Tool tool) {
        String schema = tool.inputSchema() == null ? "" : new TreeMap<>(tool.inputSchema()).toString();
        return sha256(String.join(" ",
                nullSafe(tool.name()),
                nullSafe(tool.description()).replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT),
                schema));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
