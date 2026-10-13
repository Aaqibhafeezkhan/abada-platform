package com.abada.engine.insight;

import com.abada.engine.bpmn.compatibility.BpmnValidationException;
import com.abada.engine.insight.InsightAnalyzer.Finding;
import com.abada.engine.llm.OpenAiCompatibleLlmClient;
import com.abada.engine.parser.AplParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns window findings into a concrete APL candidate. With no LLM endpoint
 * configured (or on any LLM/validation failure) it falls back to a safe,
 * semantics-preserving draft: the exact current source plus an annotated
 * header. Every candidate must parse and keep the target process id before
 * it is persisted — an invalid or non-compiling suggestion is discarded.
 */
@Component
public class InsightProposalGenerator {

    private static final Logger log = LoggerFactory.getLogger(InsightProposalGenerator.class);

    private final AplParser aplParser;
    private final OpenAiCompatibleLlmClient llm;
    private final com.abada.engine.authoring.AplAuthoringService authoring;

    public InsightProposalGenerator(OpenAiCompatibleLlmClient llm, AplParser aplParser,
            com.abada.engine.authoring.AplAuthoringService authoring) {
        this.llm = llm;
        this.aplParser = aplParser;
        this.authoring = authoring;
    }

    public record Proposal(String proposedSource, String rationale, String provider) {}

    public Proposal generate(String definitionKey, String targetSource, List<Finding> findings) {
        if (llm.isConfigured()) {
            try {
                // A patch, so the proposal's diff is as small as the finding that caused it.
                var candidate = authoring.generate(null, com.abada.engine.authoring.AplAuthoringService.Mode.REFINE,
                        requestFor(findings), targetSource);
                if (isValidSuggestion(definitionKey, candidate.aplSource())) {
                    return new Proposal(candidate.aplSource(), rationale(findings) + " (suggested by LLM"
                            + (candidate.summary() == null ? "" : ": " + candidate.summary()) + ")", "llm");
                }
                log.warn("LLM proposal for '{}' was discarded: invalid or changed process id", definitionKey);
            } catch (Exception exception) {
                log.warn("LLM proposal generation failed for '{}': {}", definitionKey, exception.getMessage());
            }
        }
        String annotated = ruleBasedWithFindings(targetSource, findings);
        return new Proposal(annotated, rationale(findings) + " (rule-based fallback)", "rule-based");
    }

    private String rationale(List<Finding> findings) {
        return findings.stream()
                .map(f -> "%s on %s (signal %s, %.2f vs threshold %.2f, %d samples)"
                        .formatted(f.summary(), f.key().nodeId(), f.signal(),
                                f.observed(), f.threshold(), f.samples()))
                .collect(Collectors.joining("; "));
    }

    /** Explanatory draft: identical executable semantics, findings documented at the top. */
    private String ruleBasedWithFindings(String targetSource, List<Finding> findings) {
        String header = "# Insight engine working draft — review before adoption\n"
                + "# Findings addressed:\n"
                + findings.stream()
                        .map(f -> "#   - %s on %s (%s)\n"
                                .formatted(f.summary(), f.key().nodeId(), f.signal()))
                        .collect(Collectors.joining())
                + "# Semantics are unchanged; adoption review decides the real change.\n";
        return header + targetSource;
    }

    public static String extractLlmBlock(String completion) {
        return OpenAiCompatibleLlmClient.extractDocument(completion);
    }

    private boolean isValidSuggestion(String definitionKey, String suggested) {
        try {
            var parsed = aplParser.parseDetailed(suggested.getBytes(StandardCharsets.UTF_8));
            return definitionKey.equals(parsed.definition().getId());
        } catch (BpmnValidationException exception) {
            return false;
        }
    }

    /** What the proposal is asked to do, in the words of the refinement endpoint. */
    private String requestFor(List<Finding> findings) {
        return "Change the process so that these findings on the running version are addressed:\n"
                + fullBlock(findings)
                + "\nMake the smallest change that addresses them. For OVERRIDE_RATE, CALIBRATION_GAP, "
                + "CONFIDENCE_DRIFT and COST_PER_CASE the node is an agent: adjust only its prompt, "
                + "confidence_threshold, model or output_schema, and keep its reviewed_by and routing as they are.";
    }

    private String fullBlock(List<Finding> findings) {
        return findings.stream()
                .map(f -> "- " + f.summary() + " (signal " + f.signal()
                        + ", observed " + f.observed() + " vs threshold " + f.threshold()
                        + ", " + f.samples() + " samples)")
                .collect(Collectors.joining("\n"));
    }

}
