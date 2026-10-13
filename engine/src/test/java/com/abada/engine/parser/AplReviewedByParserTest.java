package com.abada.engine.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.abada.engine.bpmn.compatibility.BpmnValidationException;
import com.abada.engine.bpmn.compatibility.BpmnValidationIssue;
import com.abada.engine.core.model.ParsedProcessDefinition;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** E14: an agent's {@code reviewed_by} names the human step that reviews its output. */
class AplReviewedByParserTest {
    private final AplParser parser = new AplParser();

    @Test
    void aReviewIsParsedAndChangesNoRouting() throws Exception {
        ParsedProcessDefinition definition = parser.parse(
                Files.readAllBytes(Path.of("src/test/resources/apl/review-tracked.apl.yaml")));
        assertThat(definition.getReviewOf("draft")).satisfies(review -> {
            assertThat(review.reviewNode()).isEqualTo("review");
            assertThat(review.accept()).isEqualTo("approve");
        });
        assertThat(definition.reviewsAt("review")).hasSize(1);
        assertThat(definition.reviewsAt("escalate")).isEmpty();
    }

    @Test
    void aMalformedReviewIsRejectedAtItsPath() {
        assertInvalid(variant("{ node: nowhere, accept: approve }"), "not a human-input node");
        assertInvalid(variant("{ node: done, accept: approve }"), "not a human-input node");
        assertInvalid(variant("{ node: escalate, accept: approve }"), "declares no outcomes");
        assertInvalid(variant("{ node: review, accept: maybe }"), "not an outcome of review node");
        assertThatThrownBy(() -> parser.parse(bytes(variant("{ node: review, accept: maybe }"))))
                .isInstanceOfSatisfying(BpmnValidationException.class, error ->
                        assertThat(error.getIssues()).extracting(BpmnValidationIssue::code, BpmnValidationIssue::path)
                                .contains(org.assertj.core.groups.Tuple.tuple("ABADA-APL-REVIEW-001",
                                        "/flow/nodes/1/reviewed_by/accept")));
    }

    @Test
    void aReviewThatTheAgentCannotReachIsRejected() {
        String source = """
                version: abada.io/v1
                metadata: { key: unreachable, name: Unreachable }
                flow:
                  entry: start
                  nodes:
                    - { id: start, type: webhook, next: draft }
                    - id: draft
                      type: agent
                      model: gemini-3.6-flash
                      prompt: Draft
                      reviewed_by: { node: review, accept: approve }
                      next: done
                    - id: review
                      type: human-input
                      assignees: [reviewers]
                      outcomes: { approve: { next: done }, reject: { next: done, comment: required } }
                    - { id: done, type: end }
                """;
        assertInvalid(source, "is not reachable from agent");
    }

    private void assertInvalid(String source, String message) {
        assertThatThrownBy(() -> parser.parse(bytes(source))).isInstanceOf(BpmnValidationException.class)
                .hasMessageContaining(message);
    }

    private static String variant(String reviewedBy) {
        try {
            return Files.readString(Path.of("src/test/resources/apl/review-tracked.apl.yaml"))
                    .replace("{ node: review, accept: approve }", reviewedBy);
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static byte[] bytes(String source) {
        return source.getBytes(StandardCharsets.UTF_8);
    }
}
