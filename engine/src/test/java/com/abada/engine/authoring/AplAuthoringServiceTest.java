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
    void generatesValidatedLocalFallbackWhenLlmIsNotConfigured() {
        when(llm.isConfigured()).thenReturn(false);

        var candidate = service.generate("project-1", AplAuthoringService.Mode.CREATE,
                "Review invoice fraud with a human approver", null);

        assertThat(candidate.provider()).isEqualTo(AplAuthoringService.Provider.LOCAL_FALLBACK);
        assertThat(candidate.attempts()).isZero();
        assertThat(candidate.aplSource()).contains("version: abada.io/v1", "type: webhook", "type: agent");
        assertThat(new AplParser().parse(candidate.aplSource().getBytes()).getId())
                .startsWith("review_invoice_fraud");
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
