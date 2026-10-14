package com.abada.engine.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.abada.engine.authoring.AplPatchService.PatchException;
import com.abada.engine.parser.AplParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AplPatchServiceTest {
    private static final String BASE = """
            version: abada.io/v1
            metadata: { key: claims, name: Claims }
            flow:
              entry: start
              nodes:
                - { id: start, type: webhook, next: triage }
                - { id: triage, type: agent, model: gemini-3.6-flash, prompt: Assess, next: done }
                - { id: done, type: end }
            """;
    private final AplPatchService patcher = new AplPatchService();
    private final ObjectMapper json = new ObjectMapper();

    private String apply(String operations) throws Exception {
        return patcher.apply(BASE, json.readTree(operations)).source();
    }

    @Test
    void eachOperationChangesOnlyWhatItNames() throws Exception {
        String result = apply("""
                [{"op":"addNode","after":"triage","node":{"id":"review","type":"human-input","assignees":["a"],"next":"done"}},
                 {"op":"updateNode","id":"triage","set":{"next":"review","prompt":"Assess carefully"},"unset":["model"]},
                 {"op":"updateNode","id":"triage","set":{"model":"gemini-3.6-flash"}}]""");
        var definition = new AplParser().parse(result.getBytes());
        assertThat(patcher.nodeIds(result)).containsExactly("start", "triage", "review", "done");
        assertThat(result).contains("Assess carefully");
        assertThat(definition.getUserTasks()).containsKey("review");
        // start and done are untouched.
        assertThat(result).contains("id: start").contains("next: triage");
    }

    @Test
    void removingAndRelinkingAndChangingTheEntryWork() throws Exception {
        String result = apply("""
                [{"op":"addNode","node":{"id":"intake","type":"webhook","next":"triage"}},
                 {"op":"setEntry","id":"intake"},
                 {"op":"removeNode","id":"start"}]""");
        assertThat(result).contains("entry: intake");
        assertThat(patcher.nodeIds(result)).containsExactly("triage", "done", "intake");
    }

    @Test
    void anOperationThatCannotBeAppliedIsRefusedWithTheReason() {
        assertThatThrownBy(() -> apply("[{\"op\":\"removeNode\",\"id\":\"ghost\"}]"))
                .isInstanceOf(PatchException.class).hasMessageContaining("no node 'ghost'");
        assertThatThrownBy(() -> apply("[{\"op\":\"removeNode\",\"id\":\"start\"}]"))
                .isInstanceOf(PatchException.class).hasMessageContaining("entry node");
        assertThatThrownBy(() -> apply("[{\"op\":\"addNode\",\"node\":{\"id\":\"done\",\"type\":\"end\"}}]"))
                .isInstanceOf(PatchException.class).hasMessageContaining("already exists");
        assertThatThrownBy(() -> apply("[{\"op\":\"updateNode\",\"id\":\"triage\",\"set\":{\"id\":\"renamed\"}}]"))
                .isInstanceOf(PatchException.class).hasMessageContaining("cannot be renamed");
        assertThatThrownBy(() -> apply("[{\"op\":\"updateNode\",\"id\":\"triage\",\"unset\":[\"type\"]}]"))
                .isInstanceOf(PatchException.class).hasMessageContaining("cannot be unset");
        assertThatThrownBy(() -> apply("[{\"op\":\"moveNode\",\"id\":\"triage\"}]"))
                .isInstanceOf(PatchException.class).hasMessageContaining("unknown op");
        assertThatThrownBy(() -> apply("[]")).isInstanceOf(PatchException.class);
        assertThatThrownBy(() -> patcher.parse("not json")).isInstanceOf(PatchException.class);
        assertThatThrownBy(() -> patcher.parse("{\"summary\":\"x\"}")).isInstanceOf(PatchException.class)
                .hasMessageContaining("operations");
    }

    @Test
    void aPatchIsBounded() {
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i <= AplPatchService.MAX_OPERATIONS; i++) {
            many.append(i == 0 ? "" : ",").append("{\"op\":\"setEntry\",\"id\":\"start\"}");
        }
        assertThatThrownBy(() -> apply(many.append(']').toString())).isInstanceOf(PatchException.class)
                .hasMessageContaining("at most");
    }
}
