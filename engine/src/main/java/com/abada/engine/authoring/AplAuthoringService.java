package com.abada.engine.authoring;

import com.abada.engine.bpmn.compatibility.BpmnValidationException;
import com.abada.engine.llm.OpenAiCompatibleLlmClient;
import com.abada.engine.parser.AplParser;
import com.abada.engine.persistence.repository.ProjectProcessDocumentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Generates review-only APL candidates; this service never persists workflow state. */
@Service
public class AplAuthoringService {
    public enum Mode { CREATE, REFINE }
    public enum Provider { LLM, LOCAL_FALLBACK }
    /**
     * A candidate for human review. For a refinement, {@code patch} holds the operations that were applied to the
     * base source and {@code summary} the model's one-sentence account of them; both are empty for a new process.
     */
    public record Candidate(String aplSource, Provider provider, String model,
                            int attempts, List<String> warnings, String summary, List<Map<String, Object>> patch) {
        public Candidate(String aplSource, Provider provider, String model, int attempts, List<String> warnings) {
            this(aplSource, provider, model, attempts, warnings, null, List.of());
        }
    }

    /** A refinement was asked for and no AI model is configured: no pretend change is made. */
    public static class AuthoringUnavailableException extends RuntimeException {
        public AuthoringUnavailableException(String message) {
            super(message);
        }
    }

    /** The model could not produce a valid change within the allowed attempts. */
    public static class AuthoringFailedException extends RuntimeException {
        public AuthoringFailedException(String message) {
            super(message);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(AplAuthoringService.class);
    private static final int MAX_REPAIRS = 2;
    private static final String CREATE_ROLE = """
            You are Abada Studio's APL authoring engine. Emit only one complete YAML document.
            The document must use version abada.io/v1 and compile without a BPMN/XML round-trip.
            flow.entry must reference the single webhook node. Every next, condition target, route and error
            target must reference an existing node. Use stable, descriptive node ids matching
            [a-zA-Z][a-zA-Z0-9_-]*. Never emit markdown fences or prose outside the YAML. Keep agents
            probabilistic and business rules deterministic.
            """;
    private static final String PATCH_ROLE = """
            You refine an existing Abada process. Answer with exactly one JSON object and nothing else:
            {"summary": "<one sentence saying what changed>", "operations": [ ... ]}
            Operations (use as few as the request needs, in order):
              {"op":"addNode","node":{<a complete node>},"after":"<existing node id, optional>"}
              {"op":"removeNode","id":"<node id>"}
              {"op":"updateNode","id":"<node id>","set":{<fields to set>},"unset":["<fields to remove>"]}
              {"op":"setEntry","id":"<node id>"}
            Change only what the request asks for. Do not rewrite the document, do not rename nodes (add the new
            node and remove the old one), and leave every other node untouched. After your operations every next,
            condition target, route and error target must reference an existing node.
            """;

    private final OpenAiCompatibleLlmClient llm;
    private final AplParser aplParser;
    private final ProjectProcessDocumentRepository documents;
    private final ObjectMapper objectMapper;
    private final AplPatchService patcher = new AplPatchService();
    private final String defaultAgentModel;

    public AplAuthoringService(OpenAiCompatibleLlmClient llm, AplParser aplParser,
            ProjectProcessDocumentRepository documents, ObjectMapper objectMapper,
            @Value("${abada.agent.allowed-models:" + AplParser.DEFAULT_ALLOWED_AGENT_MODELS + "}") String allowedAgentModels) {
        this.llm = llm;
        this.aplParser = aplParser;
        this.documents = documents;
        this.objectMapper = objectMapper;
        this.defaultAgentModel = allowedAgentModels.strip().split(",")[0].trim();
    }

    public Candidate generate(String projectId, Mode mode, String prompt, String baseAplSource) {
        String request = requirePrompt(prompt);
        if (mode == Mode.REFINE) return refine(request, baseAplSource);
        String name = processName(request);
        String key = uniqueKey(projectId, slug(request));

        List<String> warnings = new ArrayList<>();
        int attempts = 0;
        if (llm.isConfigured()) {
            String userPrompt = "Create a complete process for this request:\n" + request + "\n\n" + requirements(key, name);
            for (int repair = 0; repair <= MAX_REPAIRS; repair++) {
                attempts++;
                try {
                    String candidate = llm.complete(createSystem(), userPrompt);
                    String validationError = validationError(candidate, key);
                    if (validationError == null) {
                        log.info("Generated validated APL candidate for project {} key {} with {} attempt(s)",
                                projectId, key, attempts);
                        return new Candidate(candidate, Provider.LLM, llm.model(), attempts, List.copyOf(warnings));
                    }
                    warnings.add("LLM candidate " + attempts + " failed APL validation");
                    userPrompt = repairPrompt(request, key, validationError, candidate);
                } catch (Exception exception) {
                    warnings.add("LLM request " + attempts + " failed");
                    log.warn("APL authoring request failed for project {} key {} on attempt {}: {}",
                            projectId, key, attempts, exception.getMessage());
                }
            }
        } else {
            warnings.add("LLM provider is not configured");
        }

        String fallback = fallback(key, name, request);
        String fallbackError = validationError(fallback, key);
        if (fallbackError != null) throw new IllegalStateException("Local APL fallback is invalid: " + fallbackError);
        warnings.add("A deterministic local starter was generated and requires review");
        return new Candidate(fallback, Provider.LOCAL_FALLBACK, null, attempts, List.copyOf(warnings));
    }

    /**
     * Refines an existing process with a patch: the model names the operations, the engine applies them and
     * validates the result, and a failure is fed back (path and message) for a bounded number of repairs. With no
     * model configured, or when no valid change comes back, nothing is returned: a refinement is never faked.
     */
    private Candidate refine(String request, String baseAplSource) {
        if (baseAplSource == null || baseAplSource.isBlank()) {
            throw new IllegalArgumentException("baseAplSource is required for REFINE");
        }
        var parsed = aplParser.parseDetailed(baseAplSource.getBytes(StandardCharsets.UTF_8)).definition();
        String key = parsed.getId();
        if (!llm.isConfigured()) {
            throw new AuthoringUnavailableException(
                    "No AI model is configured, so this process cannot be refined. Add a provider in Settings > AI Providers.");
        }
        List<String> warnings = new ArrayList<>();
        String userPrompt = "Request:\n" + request + "\n\nCurrent process:\n" + baseAplSource;
        String lastProblem = null;
        for (int attempt = 1; attempt <= MAX_REPAIRS + 1; attempt++) {
            try {
                String answer = llm.complete(patchSystem(), userPrompt);
                JsonNode patch = patcher.parse(answer);
                var applied = patcher.apply(baseAplSource, patch.get("operations"));
                String problem = validationError(applied.source(), key);
                if (problem == null) {
                    log.info("Generated validated APL patch for key {} with {} attempt(s)", key, attempt);
                    List<Map<String, Object>> operations = objectMapper.convertValue(patch.get("operations"),
                            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                    return new Candidate(applied.source(), Provider.LLM, llm.model(), attempt,
                            List.copyOf(warnings), patch.path("summary").asText(null), operations);
                }
                lastProblem = problem;
                warnings.add("LLM patch " + attempt + " failed APL validation");
                userPrompt = "Request:\n" + request + "\n\nCurrent process (unchanged by your last answer):\n"
                        + baseAplSource + "\n\nYour last operations produced a process the engine rejects:\n" + problem
                        + "\nAnswer again with corrected operations against the current process above.";
            } catch (AplPatchService.PatchException exception) {
                lastProblem = exception.getMessage();
                warnings.add("LLM patch " + attempt + " could not be applied");
                userPrompt = "Request:\n" + request + "\n\nCurrent process:\n" + baseAplSource
                        + "\n\nYour last answer could not be applied: " + exception.getMessage()
                        + "\nAnswer again with one JSON object of operations.";
            } catch (Exception exception) {
                warnings.add("LLM request " + attempt + " failed");
                log.warn("APL refinement request failed for key {} on attempt {}: {}", key, attempt,
                        exception.getMessage());
            }
        }
        throw new AuthoringFailedException("The model could not produce a valid change after "
                + (MAX_REPAIRS + 1) + " attempts" + (lastProblem == null ? "." : ": " + lastProblem));
    }

    private String createSystem() {
        return CREATE_ROLE + "\n" + AplPromptBuilder.languageReference(allowedModels());
    }

    private String patchSystem() {
        return PATCH_ROLE + "\n" + AplPromptBuilder.languageReference(allowedModels());
    }

    private List<String> allowedModels() {
        return aplParser.allowedAgentModels().stream().sorted().toList();
    }

    private String requirements(String key, String name) {
        return "The metadata.key must be exactly \"" + key + "\" and metadata.name exactly " + quoted(name) + ".";
    }

    private String repairPrompt(String prompt, String key, String error, String candidate) {
        return "Repair the candidate below. The user request remains:\n" + prompt
                + "\nThe metadata.key must remain exactly \"" + key + "\"."
                + "\nThe engine reported:\n" + error + "\n\nInvalid candidate:\n" + candidate;
    }

    /** Null when the source is valid and keeps the key; otherwise one line per problem: path and message. */
    private String validationError(String source, String expectedKey) {
        try {
            var parsed = aplParser.parseDetailed(source.getBytes(StandardCharsets.UTF_8));
            return expectedKey.equals(parsed.definition().getId()) ? null
                    : "- metadata.key changed from " + expectedKey;
        } catch (BpmnValidationException exception) {
            return exception.getIssues().stream()
                    .map(issue -> "- " + (issue.path() == null ? "" : issue.path() + ": ") + issue.message())
                    .distinct().limit(12).collect(java.util.stream.Collectors.joining("\n"));
        }
    }

    private String uniqueKey(String projectId, String seed) {
        String candidate = seed;
        int suffix = 2;
        while (documents.findByProjectIdAndProcessKey(projectId, candidate).isPresent()) {
            String tail = "_" + suffix++;
            candidate = seed.substring(0, Math.min(seed.length(), 128 - tail.length())) + tail;
        }
        return candidate;
    }

    private static String slug(String prompt) {
        String value = Normalizer.normalize(prompt, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (value.isBlank()) value = "generated_process";
        if (!Character.isLetter(value.charAt(0))) value = "process_" + value;
        return value.substring(0, Math.min(value.length(), 96));
    }

    private static String processName(String prompt) {
        String value = prompt.strip().replaceAll("\\s+", " ");
        return value.substring(0, Math.min(value.length(), 80));
    }

    private static String requirePrompt(String prompt) {
        String value = prompt == null ? "" : prompt.strip();
        if (value.isEmpty()) throw new IllegalArgumentException("prompt is required");
        if (value.length() > 8_000) throw new IllegalArgumentException("prompt must not exceed 8000 characters");
        return value;
    }

    private String fallback(String key, String name, String prompt) {
        return """
                version: abada.io/v1
                metadata:
                  key: %s
                  name: %s
                  owner: studio-user
                  category: custom
                flow:
                  entry: start
                  nodes:
                    - id: start
                      type: webhook
                      description: Receives the initial process payload.
                      next: analyze
                    - id: analyze
                      type: agent
                      profile: abada.agent/v1
                      model: %s
                      prompt: %s
                      confidence_threshold: 85
                      temperature: 0.2
                      result_variable: analysis_result
                      next: end
                    - id: end
                      type: end
                      description: Records the terminal outcome.
                """.formatted(key, quoted(name), defaultAgentModel, quoted(prompt));
    }

    private String quoted(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Text cannot be encoded", exception);
        }
    }
}
