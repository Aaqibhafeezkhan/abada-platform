package com.abada.engine.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.abada.engine.llm.OpenAiCompatibleLlmClient;
import com.abada.engine.parser.AplParser;
import com.abada.engine.persistence.repository.ProjectProcessDocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AplAuthoringServiceTest {
    private final OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
    private final ProjectProcessDocumentRepository documents = mock(ProjectProcessDocumentRepository.class);
    private AplAuthoringService service;

    @BeforeEach
    void setUp() {
        when(documents.findByProjectIdAndProcessKey(anyString(), anyString())).thenReturn(Optional.empty());
        service = new AplAuthoringService(llm, new AplParser(), documents, new ObjectMapper(),
                AplParser.DEFAULT_ALLOWED_AGENT_MODELS);
    }

    @Test
    void createNeverFakesAProcess() throws Exception {
        // No model configured: a typed refusal, not a template that looks generated from the request.
        when(llm.isConfigured()).thenReturn(false);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.generate("project-1",
                        AplAuthoringService.Mode.CREATE, "Review invoice fraud with a human approver", null))
                .isInstanceOf(AplAuthoringService.AuthoringUnavailableException.class)
                .hasMessageContaining("Settings > AI Providers");

        // A model whose answers never validate: refused with the engine's last reason.
        when(llm.isConfigured()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn(apl("wrong_key", "Wrong"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.generate("project-1",
                        AplAuthoringService.Mode.CREATE, "Generated flow", null))
                .isInstanceOf(AplAuthoringService.AuthoringFailedException.class)
                .hasMessageContaining("3 attempts")
                .hasMessageContaining("metadata.key changed");

        // A provider that never answers says so instead of blaming the process.
        when(llm.complete(anyString(), anyString())).thenThrow(new IllegalStateException("timeout"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.generate("project-1",
                        AplAuthoringService.Mode.CREATE, "Generated flow", null))
                .isInstanceOf(AplAuthoringService.AuthoringFailedException.class)
                .hasMessageContaining("did not answer");
    }

    @Test
    void theWorkedExampleInThePromptIsValidApl() {
        String example = AplAuthoringService.CREATE_EXAMPLE.formatted("gemini-3.6-flash");
        String yaml = example.substring(example.indexOf("version: abada.io/v1"));
        var definition = new AplParser().parse(yaml.getBytes());
        assertThat(definition.getUserTasks()).containsKey("manager_approval");
        assertThat(AplStructureCheck.gaps(example.substring(0, example.indexOf("Example answer")), yaml)).isEmpty();
    }

    @Test
    void theModelIsToldHowToDecomposeARequest() throws Exception {
        when(llm.isConfigured()).thenReturn(true);
        when(llm.model()).thenReturn("test-model");
        when(llm.complete(anyString(), anyString())).thenReturn(apl("generated_flow", "Generated"));

        service.generate("project-1", AplAuthoringService.Mode.CREATE, "Generated flow", null);

        org.mockito.Mockito.verify(llm).complete(org.mockito.ArgumentMatchers.argThat(system ->
                system.contains("Never copy the whole request into one agent prompt")
                        && system.contains("type: condition") && system.contains("type: human-input")), anyString());
    }

    @Test
    void serverRoomRequestIsNotFoldedIntoOneAgent() throws Exception {
        String request = "scan the id card and retrive the user name and function with ocr, then chek if the user "
                + "can access the server room, if he's not in devops user list, redirect to the adminstrator for "
                + "validation, if he is in at list devops list , give him access ,end";
        when(llm.isConfigured()).thenReturn(true);
        when(llm.model()).thenReturn("test-model");
        // First the whole request in one agent (the reported defect), then the decomposed process.
        when(llm.complete(anyString(), anyString())).thenAnswer(call -> {
            String key = keyIn(call.getArgument(1, String.class));
            return call.getArgument(1, String.class).contains("no condition, decision-table or routing node")
                    ? serverRoom(key) : singleAgent(key);
        });

        var candidate = service.generate("project-1", AplAuthoringService.Mode.CREATE, request, null);

        assertThat(candidate.attempts()).isEqualTo(2);
        assertThat(candidate.warnings()).containsExactly("LLM candidate 1 left part of the request out of the process");
        var definition = new AplParser().parse(candidate.aplSource().getBytes());
        assertThat(definition.getUserTasks()).containsKey("admin_validation");
        assertThat(candidate.aplSource()).contains("type: condition", "type: engine-task");
        assertThat(java.util.regex.Pattern.compile("(?m)^\\s+type: end$").matcher(candidate.aplSource())
                .results().count()).isEqualTo(2);
    }

    @Test
    void aCandidateThatStaysIncompleteIsReturnedWithWhatItMisses() throws Exception {
        when(llm.isConfigured()).thenReturn(true);
        when(llm.model()).thenReturn("test-model");
        when(llm.complete(anyString(), anyString())).thenAnswer(call -> singleAgent(keyIn(call.getArgument(1, String.class))));

        var candidate = service.generate("project-1", AplAuthoringService.Mode.CREATE,
                "If the amount is high, a manager approves it", null);

        assertThat(candidate.attempts()).isEqualTo(3);
        assertThat(candidate.warnings()).anySatisfy(warning -> assertThat(warning).contains("no condition"))
                .anySatisfy(warning -> assertThat(warning).contains("no human-input"));
    }

    @Test
    void structureCheckReadsOnlyWhatTheRequestPlainlyAsks() {
        String agentOnly = singleAgent("k");
        assertThat(AplStructureCheck.gaps("Summarise the support ticket", agentOnly)).isEmpty();
        assertThat(AplStructureCheck.gaps("Classify the email, otherwise archive it", agentOnly)).hasSize(1);
        assertThat(AplStructureCheck.gaps("Send it to an administrator for validation", agentOnly)).hasSize(1);
        assertThat(AplStructureCheck.gaps("If valid, approve", "not: [yaml")).isEmpty();
    }

    @Test
    void repairsInvalidLlmCandidateBeforeReturningIt() throws Exception {
        when(llm.isConfigured()).thenReturn(true);
        when(llm.model()).thenReturn("test-model");
        when(llm.complete(anyString(), anyString())).thenReturn("not apl", apl("generated_flow", "Generated"));

        var candidate = service.generate("project-1", AplAuthoringService.Mode.CREATE,
                "Generated flow", null);

        assertThat(candidate.provider()).isEqualTo(AplAuthoringService.Provider.LLM);
        assertThat(candidate.model()).isEqualTo("test-model");
        assertThat(candidate.attempts()).isEqualTo(2);
        assertThat(candidate.warnings()).anySatisfy(warning ->
                assertThat(warning).contains("failed APL validation"));
    }

    @Test
    void refineAppliesTheModelsOperationsAndKeepsTheIdentity() throws Exception {
        when(llm.isConfigured()).thenReturn(true);
        when(llm.model()).thenReturn("test-model");
        when(llm.complete(anyString(), anyString())).thenReturn("""
                {"summary":"Adds a review before the end","operations":[
                  {"op":"addNode","node":{"id":"review","type":"human-input","assignees":["reviewers"],"next":"end"},"after":"start"},
                  {"op":"updateNode","id":"start","set":{"next":"review"}}]}""");

        var candidate = service.generate("project-1", AplAuthoringService.Mode.REFINE,
                "Add a review", apl("stable_key", "Stable"));

        assertThat(candidate.provider()).isEqualTo(AplAuthoringService.Provider.LLM);
        assertThat(candidate.attempts()).isEqualTo(1);
        assertThat(candidate.summary()).isEqualTo("Adds a review before the end");
        assertThat(candidate.patch()).hasSize(2);
        var definition = new AplParser().parse(candidate.aplSource().getBytes());
        assertThat(definition.getId()).isEqualTo("stable_key");
        assertThat(definition.getUserTasks()).containsKey("review");
    }

    @Test
    void refineFeedsTheEnginesReasonBackAndRepairsWithinTheBound() throws Exception {
        when(llm.isConfigured()).thenReturn(true);
        when(llm.model()).thenReturn("test-model");
        // First a node that points nowhere, then a corrected patch.
        when(llm.complete(anyString(), anyString())).thenReturn(
                "{\"operations\":[{\"op\":\"updateNode\",\"id\":\"start\",\"set\":{\"next\":\"nowhere\"}}]}",
                "{\"operations\":[{\"op\":\"updateNode\",\"id\":\"start\",\"set\":{\"description\":\"Entry\"}}]}");

        var candidate = service.generate("project-1", AplAuthoringService.Mode.REFINE,
                "Describe the entry", apl("stable_key", "Stable"));

        assertThat(candidate.attempts()).isEqualTo(2);
        assertThat(candidate.warnings()).anySatisfy(warning -> assertThat(warning).contains("failed APL validation"));
        org.mockito.Mockito.verify(llm, org.mockito.Mockito.times(2)).complete(anyString(), org.mockito.ArgumentMatchers
                .argThat(user -> user.contains("is not a declared node") || user.contains("Describe the entry")));
    }

    @Test
    void refineNeverFakesAChange() throws Exception {
        // No model configured: a typed refusal, not a starter that replaces the process.
        when(llm.isConfigured()).thenReturn(false);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.generate("project-1",
                        AplAuthoringService.Mode.REFINE, "Add a review", apl("stable_key", "Stable")))
                .isInstanceOf(AplAuthoringService.AuthoringUnavailableException.class);

        // A model that never returns a usable patch: refused after the bounded attempts.
        when(llm.isConfigured()).thenReturn(true);
        when(llm.complete(anyString(), anyString())).thenReturn("I would add a review step.");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.generate("project-1",
                        AplAuthoringService.Mode.REFINE, "Add a review", apl("stable_key", "Stable")))
                .isInstanceOf(AplAuthoringService.AuthoringFailedException.class)
                .hasMessageContaining("3 attempts");
        org.mockito.Mockito.verify(llm, org.mockito.Mockito.times(3)).complete(anyString(), anyString());
    }

    private static String keyIn(String userPrompt) {
        var matcher = java.util.regex.Pattern.compile("metadata.key must (?:be|remain) exactly \"([^\"]+)\"")
                .matcher(userPrompt);
        return matcher.find() ? matcher.group(1) : "missing";
    }

    private static String singleAgent(String key) {
        return """
                version: abada.io/v1
                metadata:
                  key: %s
                  name: Generated
                flow:
                  entry: start
                  nodes:
                    - id: start
                      type: webhook
                      next: analyze
                    - id: analyze
                      type: agent
                      model: gemini-3.6-flash
                      prompt: Do everything the request says.
                      result_variable: analysis_result
                      next: end
                    - id: end
                      type: end
                """.formatted(key);
    }

    private static String serverRoom(String key) {
        return """
                version: abada.io/v1
                metadata:
                  key: %s
                  name: Server room access
                flow:
                  entry: start
                  nodes:
                    - id: start
                      type: webhook
                      next: read_id_card
                    - id: read_id_card
                      type: agent
                      model: gemini-3.6-flash
                      prompt: Read the holder's full name and job function from the ID card image.
                      result_variable: badge
                      next: check_devops
                    - id: check_devops
                      type: condition
                      rules:
                        - if: badge.function == "devops"
                          then: grant_access
                        - else: true
                          then: admin_validation
                    - id: admin_validation
                      type: human-input
                      description: Validate server-room access for a user outside DevOps
                      assignees: [administrators]
                      outcomes:
                        approve: { next: grant_access }
                        reject: { next: denied }
                    - id: grant_access
                      type: engine-task
                      service: abada:grant-server-room-access
                      next: granted
                    - id: granted
                      type: end
                    - id: denied
                      type: end
                """.formatted(key);
    }

    private String apl(String key, String name) {
        return """
                version: abada.io/v1
                metadata:
                  key: %s
                  name: %s
                flow:
                  entry: start
                  nodes:
                    - id: start
                      type: webhook
                      next: end
                    - id: end
                      type: end
                """.formatted(key, name);
    }
}
