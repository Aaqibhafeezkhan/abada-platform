package com.abada.engine.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.abada.engine.bpmn.compatibility.BpmnValidationException;
import com.abada.engine.core.model.ParsedProcessDefinition;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** E21: a for-each is bounded by what its author wrote, never by default. */
class AplForEachParserTest {
    private final AplParser parser = new AplParser();

    private static String fixture() throws Exception {
        return Files.readString(Path.of("src/test/resources/apl/for-each-batch.apl.yaml"));
    }

    private void invalid(String source, String message) {
        assertThatThrownBy(() -> parser.parse(source.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BpmnValidationException.class).hasMessageContaining(message);
    }

    @Test
    void aForEachIsParsedAndIsAlsoACallSoItsChildIsPinnedAndCancelledLikeOne() throws Exception {
        ParsedProcessDefinition definition = parser.parse(fixture().getBytes(StandardCharsets.UTF_8));
        var loop = definition.getForEach("check_each");
        assertThat(loop.itemsExpression()).isEqualTo("${documents}");
        assertThat(loop.itemInput()).isEqualTo("document");
        assertThat(loop.maxItems()).isEqualTo(20);
        assertThat(loop.maxParallel()).isEqualTo(3);
        assertThat(loop.resultsVariable()).isEqualTo("check_results");
        var call = definition.getCallProcess("check_each");
        assertThat(call.processKey()).isEqualTo("document_check");
        assertThat(call.inputs()).containsKeys("case_id", "document");
        assertThat(call.outputs()).containsEntry("verdict", "verdict");
        assertThat(definition.getForEach("decide")).isNull();
    }

    @Test
    void theBoundsAreRequiredAndCapped() throws Exception {
        String source = fixture();
        invalid(source.replace("      max_items: 20\n", ""), "requires 'max_items'");
        invalid(source.replace("max_items: 20", "max_items: 1001"), "between 1 and 1000");
        invalid(source.replace("max_items: 20", "max_items: 0"), "between 1 and 1000");
        invalid(source.replace("max_parallel: 3", "max_parallel: 51"), "between 1 and 50");
        invalid(source.replace("      results: check_results\n", ""), "requires 'results'");
        invalid(source.replace("      item_input: document\n", ""), "requires 'item_input'");
        invalid(source.replace("items: \"${documents}\"", "items: \"\""), "requires 'items'");
        invalid(source.replace("items: \"${documents}\"", "items: \"documents ==\""), "not a valid expression");
        invalid(source.replace("      next: decide\n", ""), "requires 'next'");
    }

    @Test
    void theItemInputIsSetByTheEngineAndTheNodeCannotCallItself() throws Exception {
        String source = fixture();
        invalid(source.replace("inputs: { case_id: \"${case_id}\" }",
                "inputs: { case_id: \"${case_id}\", document: \"${x}\" }"), "sets 'document' itself");
        invalid(source.replace("process: document_check", "process: kyc_batch"), "calls its own process");
        invalid(source.replace("outputs: { verdict: verdict }", "outputs: {}"), "declares no outputs");
    }
}
