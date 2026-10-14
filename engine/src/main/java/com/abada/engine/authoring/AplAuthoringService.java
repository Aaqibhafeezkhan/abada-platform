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
    public enum Provider { LLM }
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

    /** No AI model is configured: no pretend process or change is made. */
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

            Turn the request into a process, one node per step. Never copy the whole request into one agent prompt.
            - Reading, extracting, classifying or summarising unstructured input (documents, images, OCR, free
              text): an agent with a short prompt for that step only and a result_variable for what it produces.
            - "If", "check whether", "when ... otherwise" over data the process holds: a condition whose CEL rules
              end with an else rule, or a decision-table. An agent never makes these decisions.
            - Approval, validation, review or sign-off by a person or role: a human-input with assignees naming
              that role, and outcomes (approve, reject) when the next step depends on the answer.
            - Performing an action in another system (grant access, pay, notify, update a record): an engine-task
              with service abada:<action-name>.
            - Every path reaches an end node; use separate end nodes when the outcomes differ. When the request does
              not say what happens on a branch (for example after a rejection), stop without performing the action.
            """;
    /** A worked decomposition shown to the model; {@code %s} is the default agent model. Kept valid by a test. */
    static final String CREATE_EXAMPLE = """
            Example request: Read the amount from the invoice; if it is over 1000 a manager must approve it,
            otherwise pay it.
            Example answer:
            version: abada.io/v1
            metadata:
              key: invoice_payment
              name: Invoice payment
            flow:
              entry: start
              nodes:
                - id: start
                  type: webhook
                  next: read_invoice
                - id: read_invoice
                  type: agent
                  model: %s
                  prompt: Read the total amount from the invoice document.
                  result_variable: invoice
                  next: check_amount
                - id: check_amount
                  type: condition
                  rules:
                    - if: invoice.amount > 1000
                      then: manager_approval
                    - else: true
                      then: pay_invoice
                - id: manager_approval
                  type: human-input
                  description: Approve an invoice over 1000
                  assignees: [managers]
                  outcomes:
                    approve: { next: pay_invoice }
                    reject: { next: rejected }
                - id: pay_invoice
                  type: engine-task
                  service: abada:pay-invoice
                  next: paid
                - id: paid
                  type: end
                - id: rejected
                  type: end
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

        if (!llm.isConfigured()) {
            throw new AuthoringUnavailableException("No AI model is configured, so no process was generated from "
                    + "your description. Add a provider in Settings > AI Providers, or start from a template.");
        }
        List<String> warnings = new ArrayList<>();
        String userPrompt = "Create a complete process for this request:\n" + request + "\n\n" + requirements(key, name);
        String lastProblem = null;
        String incomplete = null;
        List<String> incompleteGaps = List.of();
        for (int attempt = 1; attempt <= MAX_REPAIRS + 1; attempt++) {
            try {
                String candidate = llm.complete(createSystem(), userPrompt);
                String validationError = validationError(candidate, key);
                if (validationError == null) {
                    List<String> gaps = AplStructureCheck.gaps(request, candidate);
                    if (gaps.isEmpty()) {
                        log.info("Generated validated APL candidate for project {} key {} with {} attempt(s)",
                                projectId, key, attempt);
                        return new Candidate(candidate, Provider.LLM, llm.model(), attempt, List.copyOf(warnings));
                    }
                    // Valid, but a step the request asks for is missing: keep it in case no better one comes.
                    incomplete = candidate;
                    incompleteGaps = gaps;
                    warnings.add("LLM candidate " + attempt + " left part of the request out of the process");
                    userPrompt = repairPrompt(request, key, "- " + String.join("\n- ", gaps), candidate);
                    continue;
                }
                lastProblem = validationError;
                warnings.add("LLM candidate " + attempt + " failed APL validation");
                userPrompt = repairPrompt(request, key, validationError, candidate);
            } catch (Exception exception) {
                warnings.add("LLM request " + attempt + " failed");
                log.warn("APL authoring request failed for project {} key {} on attempt {}: {}",
                        projectId, key, attempt, exception.getMessage());
            }
        }
        if (incomplete != null) {
            warnings.addAll(incompleteGaps);
            return new Candidate(incomplete, Provider.LLM, llm.model(), MAX_REPAIRS + 1, List.copyOf(warnings));
        }
        throw new AuthoringFailedException("The model could not produce a valid process after "
                + (MAX_REPAIRS + 1) + " attempts" + (lastProblem == null
                        ? ": the AI provider did not answer. Check the provider in Settings > AI Providers."
                        : ". The engine rejected the last candidate:\n" + lastProblem));
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
        return CREATE_ROLE + "\n" + CREATE_EXAMPLE.formatted(defaultAgentModel) + "\n"
                + AplPromptBuilder.languageReference(allowedModels());
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

    private String quoted(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Text cannot be encoded", exception);
        }
    }
}
