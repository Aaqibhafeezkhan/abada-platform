package com.abada.engine.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.abada.engine.parser.AplParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** E17: the prompt is built from the schema, so it lists every node type the engine accepts. */
class AplPromptBuilderTest {
    private static final List<String> MODELS = List.of("gemini-3.6-flash", "gemini-3.7-flash");

    @Test
    void everyNodeTypeOfTheSchemaAndItsRequiredFieldsAreInThePrompt() {
        String reference = AplPromptBuilder.languageReference(MODELS);
        var types = AplPromptBuilder.nodeTypes();
        assertThat(types).containsKeys("webhook", "agent", "human-input", "call-process", "decision-table", "timer",
                "condition", "engine-task", "end");
        types.forEach((type, required) -> {
            assertThat(reference).as("node type %s", type).contains("- " + type + ": ");
            required.stream().filter(field -> !field.equals("type"))
                    .forEach(field -> assertThat(reference).as("%s requires %s", type, field).contains(field + "*"));
        });
    }

    @Test
    void theFieldsAndModelsOfTheRunningEngineAreWhatTheModelIsToldAbout() {
        String reference = AplPromptBuilder.languageReference(MODELS);
        assertThat(reference).contains("reviewed_by", "confidence_threshold", "on_low_confidence", "fallback_models",
                "delegates", "routes", "gemini-3.6-flash|gemini-3.7-flash");
        // Nothing is invented: every node type named in the prompt is one the parser accepts.
        for (String type : AplPromptBuilder.nodeTypes().keySet()) {
            assertThat(AplParser.SUPPORTED_TYPES).as("parser supports %s", type).contains(type);
        }
    }
}
