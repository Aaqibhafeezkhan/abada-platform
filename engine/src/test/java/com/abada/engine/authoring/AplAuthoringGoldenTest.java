package com.abada.engine.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.abada.engine.core.model.ParsedProcessDefinition;
import com.abada.engine.llm.OpenAiCompatibleLlmClient;
import com.abada.engine.parser.AplParser;
import com.abada.engine.persistence.repository.ProjectProcessDocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * E17: recorded model answers for a corpus of requests, replayed offline and deterministically. Each case says
 * what the resulting process must contain, which nodes a refinement must leave exactly as they were, and how many
 * attempts the repair loop needed. A separate, opt-in run against a real provider is outside CI.
 */
class AplAuthoringGoldenTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final YAMLMapper YAML = new YAMLMapper();
    private static final Pattern IDENTITY = Pattern.compile("metadata.key must be exactly \"([^\"]+)\" and metadata.name exactly (\"[^\"]*\")");

    @TestFactory
    Stream<DynamicTest> everyRecordedRequestProducesWhatItClaims() throws Exception {
        JsonNode corpus = JSON.readTree(Files.readString(Path.of("src/test/resources/authoring/golden.json")));
        assertThat(corpus.size()).isGreaterThanOrEqualTo(12);
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode golden : corpus) {
            tests.add(DynamicTest.dynamicTest(golden.path("name").asText(), () -> check(golden)));
        }
        return tests.stream();
    }

    private void check(JsonNode golden) throws Exception {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        ProjectProcessDocumentRepository documents = mock(ProjectProcessDocumentRepository.class);
        when(documents.findByProjectIdAndProcessKey(anyString(), anyString())).thenReturn(Optional.empty());
        when(llm.isConfigured()).thenReturn(true);
        when(llm.model()).thenReturn("recorded");
        Deque<String> answers = new ArrayDeque<>();
        golden.path("responses").forEach(answer -> answers.add(answer.asText()));
        when(llm.complete(anyString(), anyString())).thenAnswer(call -> {
            String answer = answers.poll();
            Matcher identity = IDENTITY.matcher(call.getArgument(1, String.class));
            if (identity.find()) answer = answer.replace("{{KEY}}", identity.group(1)).replace("{{NAME}}", identity.group(2));
            return answer;
        });
        var service = new AplAuthoringService(llm, new AplParser(), documents, new ObjectMapper(),
                AplParser.DEFAULT_ALLOWED_AGENT_MODELS);
        boolean refine = golden.path("mode").asText().equals("REFINE");
        String base = refine ? golden.path("base").asText() : null;

        var candidate = service.generate("project", refine ? AplAuthoringService.Mode.REFINE
                : AplAuthoringService.Mode.CREATE, golden.path("prompt").asText(), base);

        assertThat(candidate.provider()).isEqualTo(AplAuthoringService.Provider.LLM);
        ParsedProcessDefinition definition = new AplParser().parse(candidate.aplSource().getBytes(StandardCharsets.UTF_8));
        List<String> ids = nodeIds(candidate.aplSource());
        golden.path("expectIds").forEach(id -> assertThat(ids).contains(id.asText()));
        golden.path("absentIds").forEach(id -> assertThat(ids).doesNotContain(id.asText()));
        Pattern types = Pattern.compile("(?m)^\\s+type: ([a-z-]+)");
        Matcher found = types.matcher(candidate.aplSource());
        List<String> present = new ArrayList<>();
        while (found.find()) present.add(found.group(1));
        golden.path("expectTypes").forEach(type -> assertThat(present).contains(type.asText()));
        if (golden.has("attempts")) assertThat(candidate.attempts()).isEqualTo(golden.path("attempts").asInt());
        if (refine) {
            assertThat(definition.getId()).isEqualTo("claims");
            assertThat(candidate.patch()).isNotEmpty();
            if (golden.has("expectSummary")) assertThat(candidate.summary()).isEqualTo(golden.path("expectSummary").asText());
            // A refinement leaves every node it does not name exactly as it was.
            JsonNode before = YAML.readTree(base).path("flow").path("nodes");
            JsonNode after = YAML.readTree(candidate.aplSource()).path("flow").path("nodes");
            for (JsonNode id : golden.path("unchangedIds")) {
                assertThat(byId(after, id.asText())).as("node %s", id.asText()).isEqualTo(byId(before, id.asText()));
            }
        } else {
            assertThat(candidate.patch()).isEmpty();
        }
    }

    private static List<String> nodeIds(String source) throws Exception {
        List<String> ids = new ArrayList<>();
        YAML.readTree(source).path("flow").path("nodes").forEach(node -> ids.add(node.path("id").asText()));
        return ids;
    }

    private static JsonNode byId(JsonNode nodes, String id) {
        for (JsonNode node : nodes) if (node.path("id").asText().equals(id)) return node;
        return null;
    }
}
