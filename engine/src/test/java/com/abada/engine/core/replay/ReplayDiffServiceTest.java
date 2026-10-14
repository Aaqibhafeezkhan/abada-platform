package com.abada.engine.core.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.abada.engine.core.replay.ReplayRunner.Kind;
import com.abada.engine.core.replay.ReplayRunner.Step;
import com.abada.engine.persistence.entity.ActivityHistoryEntity;
import com.abada.engine.persistence.repository.ActivityHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ReplayDiffServiceTest {
    private final ReplayDiffService diff = new ReplayDiffService();

    private static Step agent(String outcome) { return new Step("draft", Kind.AGENT, outcome); }
    private static Step human(String outcome) { return new Step("review", Kind.HUMAN, outcome); }

    @Test
    void identicalPathsAreUnchanged() {
        assertThat(diff.compare(List.of(agent("OK"), human("approve")), List.of(agent("OK"), human("approve")))
                .changed()).isFalse();
    }

    @Test
    void aChangedOutcomeIsReportedAtItsPosition() {
        var result = diff.compare(List.of(agent("OK"), human("reject")), List.of(agent("LOW_CONFIDENCE"), human("reject")));
        assertThat(result.changed()).isTrue();
        assertThat(result.firstDivergence().position()).isZero();
        assertThat(result.changedOutcomes()).containsExactly(agent("LOW_CONFIDENCE"));
    }

    @Test
    void stepsThatOnlyOnePathTookAreAddedOrRemoved() {
        var result = diff.compare(List.of(agent("OK"), human("reject"), agent("OK"), human("approve")),
                List.of(agent("OK"), human("approve")));
        assertThat(result.changed()).isTrue();
        assertThat(result.removedSteps()).contains("agent:draft=OK", "human:review=reject");
        assertThat(result.addedSteps()).isEmpty();
        assertThat(result.firstDivergence().original()).isEqualTo(human("reject"));
        assertThat(result.firstDivergence().replayed()).isEqualTo(human("approve"));
    }

    @Test
    void aReplayThatStopsEarlyIsADivergenceAtTheMissingStep() {
        var result = diff.compare(List.of(agent("OK"), human("approve")), List.of(agent("OK")));
        assertThat(result.firstDivergence().position()).isEqualTo(1);
        assertThat(result.firstDivergence().replayed()).isNull();
    }

    @Test
    void anOverrideIsAReviewOutcomeOtherThanTheAcceptingOne() {
        assertThat(ReplayDiffService.overridden(List.of(agent("OK"), human("reject")), "review", "approve")).isTrue();
        assertThat(ReplayDiffService.overridden(List.of(agent("OK"), human("approve")), "review", "approve")).isFalse();
    }

    @Test
    void historyIsReadAsDecisionsAndRetriesAreLeftOut() {
        OriginalPathReader reader = new OriginalPathReader(Mockito.mock(ActivityHistoryRepository.class),
                new ObjectMapper());
        List<Step> path = reader.steps(List.of(
                event("PROCESS_STARTED", "start", "{}"),
                event("EXTERNAL_TASK_OUTPUT_REJECTED", "draft", "{\"agentOutcome\":\"INVALID_OUTPUT\"}"),
                event("EXTERNAL_TASK_COMPLETED", "draft", "{\"agentOutcome\":\"OK\"}"),
                event("DECISION_TABLE_APPLIED", "rules", "{\"matchedRuleIndexes\":[2]}"),
                event("TASK_COMPLETED", "review", "{\"outcome\":\"reject\",\"commented\":true}"),
                event("TASK_COMPLETED", "notify", "{}")));
        assertThat(path).containsExactly(agent("OK"), new Step("rules", Kind.DECISION, "[2]"), human("reject"),
                new Step("notify", Kind.HUMAN, "completed"));
        var decisions = OriginalPathReader.decisionsOf(path);
        assertThat(decisions.next("review")).contains("reject");
        assertThat(decisions.next("review")).isEmpty();
    }

    private static ActivityHistoryEntity event(String type, String activity, String details) {
        ActivityHistoryEntity event = new ActivityHistoryEntity();
        event.setEventType(type);
        event.setActivityId(activity);
        event.setDetailsJson(details);
        return event;
    }
}
