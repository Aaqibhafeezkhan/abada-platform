package com.abada.engine.core.delegation;

import static com.abada.engine.core.delegation.PostgresCallProcessTest.PROJECT;
import static com.abada.engine.core.delegation.PostgresCallProcessTest.deploy;
import static com.abada.engine.core.delegation.PostgresCallProcessTest.deployFixture;
import static com.abada.engine.core.delegation.PostgresCallProcessTest.events;
import static com.abada.engine.core.delegation.PostgresCallProcessTest.runJobs;
import static org.assertj.core.api.Assertions.assertThat;

import com.abada.engine.core.AbadaEngine;
import com.abada.engine.core.ProcessInstance;
import com.abada.engine.core.model.BoundaryMeta;
import com.abada.engine.core.model.ProcessStatus;
import com.abada.engine.dto.LineageDto;
import com.abada.engine.persistence.entity.IncidentEntity;
import com.abada.engine.persistence.repository.ForEachItemRepository;
import com.abada.engine.persistence.repository.IncidentRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * E21 under the PostgreSQL authority: a for-each runs a child per item at most {@code max_parallel} at a time,
 * gathers the results in item order, lets an item fail without failing the others, and stops cleanly when the
 * parent is cancelled or leaves the step.
 */
@Testcontainers
class PostgresForEachTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("abada_for_each").withUsername("abada").withPassword("abada");

    private static ConfigurableApplicationContext context;

    @BeforeAll
    static void start() {
        context = PostgresCallProcessTest.startApplication(POSTGRES, false);
        deployFixture(context, "for-each-item.apl.yaml");
        deployFixture(context, "for-each-batch.apl.yaml");
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
    }

    /**
     * Completes the check of one child. A worker that fetches locks every open check at once, so the ones it
     * has locked before are looked up again by instance rather than fetched a second time.
     */
    private static void completeItem(String child, Map<String, Object> variables) {
        var workers = context.getBean(com.abada.engine.core.ExternalTaskCommandService.class);
        workers.fetchAndLock(new com.abada.engine.dto.FetchAndLockRequest("w", List.of("doc.check"), 600_000L, 50));
        var task = context.getBean(com.abada.engine.persistence.repository.ExternalTaskRepository.class).findAll()
                .stream().filter(row -> row.getProcessInstanceId().equals(child)
                        && "doc.check".equals(row.getTopicName()) && "w".equals(row.getWorkerId())
                        && row.getStatus() == com.abada.engine.persistence.entity.ExternalTaskEntity.Status.LOCKED)
                .findFirst().orElseThrow(() -> new AssertionError("no open check for " + child));
        workers.complete(task.getId(), "w", new java.util.LinkedHashMap<>(variables));
    }

    private static List<String> docs(int count) {
        List<String> docs = new ArrayList<>();
        for (int i = 0; i < count; i++) docs.add("d" + i);
        return docs;
    }

    private static String startBatch(List<?> documents) {
        return context.getBean(AbadaEngine.class).startProcess(PROJECT, "kyc_batch", "alice",
                Map.of("case_id", "c-1", "documents", documents)).getId();
    }

    private static List<String> running(String parent) {
        AbadaEngine engine = context.getBean(AbadaEngine.class);
        return engine.lineage(parent).children().stream().map(LineageDto.Link::instanceId)
                .filter(id -> engine.getProcessInstanceById(id).getStatus() == ProcessStatus.RUNNING).toList();
    }

    private static String documentOf(String child) {
        return String.valueOf(context.getBean(AbadaEngine.class).getProcessInstanceById(child).getVariables()
                .get("document"));
    }

    @Test
    void itemsRunAtMostMaxParallelAtATimeAndResultsComeBackInItemOrder() {
        AbadaEngine engine = context.getBean(AbadaEngine.class);
        String parent = startBatch(docs(7));
        assertThat(running(parent)).hasSize(3);
        assertThat(engine.lineage(parent).children()).hasSize(3);

        // Finish them out of order; the window refills and never exceeds three.
        int rounds = 0;
        while (engine.getProcessInstanceById(parent).getActiveTokens().contains("check_each") && rounds++ < 20) {
            List<String> now = running(parent);
            assertThat(now.size()).isLessThanOrEqualTo(3);
            String child = now.get(now.size() - 1);
            completeItem(child, Map.of("verdict", "ok-" + documentOf(child)));
            runJobs(context);
        }

        ProcessInstance done = engine.getProcessInstanceById(parent);
        assertThat(done.getActiveTokens()).containsExactly("decide");
        assertThat(engine.lineage(parent).children()).hasSize(7);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) done.getVariables().get("check_results");
        assertThat(results).hasSize(7);
        for (int i = 0; i < 7; i++) {
            assertThat(results.get(i)).containsEntry("index", i).containsEntry("ok", true);
            assertThat(results.get(i).get("outputs")).isEqualTo(Map.of("verdict", "ok-d" + i));
        }
        assertThat(done.getVariables()).containsEntry("check_each_outcome", "OK");
        List<String> history = events(context, parent);
        assertThat(history.stream().filter("FOR_EACH_STARTED"::equals)).hasSize(1);
        assertThat(history.stream().filter("FOR_EACH_ITEM_DONE"::equals)).hasSize(7);
        assertThat(history.stream().filter("FOR_EACH_COMPLETED"::equals)).hasSize(1);
        // Only declared outputs return, and no item value is written to the history.
        assertThat(context.getBean(com.abada.engine.persistence.repository.ActivityHistoryRepository.class)
                .findByProcessInstanceIdOrderByOccurredAtAsc(parent).stream()
                .map(com.abada.engine.persistence.entity.ActivityHistoryEntity::getDetailsJson).toList())
                .noneMatch(details -> details.contains("\"d3\"") || details.contains("ok-d"));
    }

    @Test
    void aFailedItemRoutesThroughOnErrorOnceAfterTheOthersHaveFinished() {
        AbadaEngine engine = context.getBean(AbadaEngine.class);
        String parent = startBatch(docs(5));
        String failing = running(parent).stream().filter(child -> documentOf(child).equals("d1")).findFirst()
                .orElseThrow();
        engine.cancelProcessInstance(failing, "the document is unreadable");
        runJobs(context);

        // The others still run to the end; the parent has not moved yet.
        assertThat(engine.getProcessInstanceById(parent).getActiveTokens()).containsExactly("check_each");
        int rounds = 0;
        while (!running(parent).isEmpty() && rounds++ < 20) {
            String child = running(parent).getFirst();
            completeItem(child, Map.of("verdict", "ok-" + documentOf(child)));
            runJobs(context);
        }

        ProcessInstance routed = engine.getProcessInstanceById(parent);
        assertThat(routed.getActiveTokens()).containsExactly("manual");
        assertThat(routed.getVariables()).containsEntry("check_each_error_code", "FOR_EACH_ITEM_FAILED");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) routed.getVariables().get("check_results");
        assertThat(results).hasSize(5);
        assertThat(results.get(1)).containsEntry("ok", false).containsEntry("error", "CHILD_CANCELLED");
        assertThat(results.stream().filter(entry -> Boolean.TRUE.equals(entry.get("ok")))).hasSize(4);
        // Four children completed; none of them was cancelled to make way for the failure.
        assertThat(engine.lineage(parent).children().stream()
                .filter(link -> engine.getProcessInstanceById(link.instanceId()).getStatus() == ProcessStatus.COMPLETED))
                .hasSize(4);
    }

    @Test
    void anEmptyListPassesStraightOnAndTooLongAListStartsNothing() {
        AbadaEngine engine = context.getBean(AbadaEngine.class);
        String empty = startBatch(List.of());
        ProcessInstance passed = engine.getProcessInstanceById(empty);
        assertThat(passed.getActiveTokens()).containsExactly("decide");
        assertThat(passed.getVariables()).containsEntry("check_results", List.of());
        assertThat(engine.lineage(empty).children()).isEmpty();

        String tooMany = startBatch(docs(25));
        ProcessInstance refused = engine.getProcessInstanceById(tooMany);
        assertThat(refused.getActiveTokens()).containsExactly("manual");
        assertThat(refused.getVariables()).containsEntry("check_each_error_code", "FOR_EACH_TOO_MANY");
        assertThat(engine.lineage(tooMany).children()).isEmpty();

        String notAList = context.getBean(AbadaEngine.class).startProcess(PROJECT, "kyc_batch", "alice",
                Map.of("case_id", "c-2", "documents", "just one")).getId();
        assertThat(engine.getProcessInstanceById(notAList).getVariables())
                .containsEntry("check_each_error_code", "FOR_EACH_ITEMS_INVALID");
        assertThat(engine.lineage(notAList).children()).isEmpty();
    }

    @Test
    void anItemOfTheWrongTypeFailsAloneAndTheOthersStillRun() {
        AbadaEngine engine = context.getBean(AbadaEngine.class);
        // The child declares `document` a string: the number cannot be handed to it.
        String parent = startBatch(List.of("a", 7, "c"));
        assertThat(running(parent)).hasSize(2);
        for (String child : running(parent)) {
            completeItem(child, Map.of("verdict", "ok"));
        }
        runJobs(context);
        ProcessInstance routed = engine.getProcessInstanceById(parent);
        assertThat(routed.getActiveTokens()).containsExactly("manual");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) routed.getVariables().get("check_results");
        assertThat(results.get(1)).containsEntry("ok", false).containsEntry("error", "CHILD_INPUT_INVALID");
        assertThat(results.get(0)).containsEntry("ok", true);
        assertThat(results.get(2)).containsEntry("ok", true);
    }

    @Test
    void cancellingTheParentStopsRunningAndPendingItems() {
        AbadaEngine engine = context.getBean(AbadaEngine.class);
        String parent = startBatch(docs(7));
        List<String> started = running(parent);
        assertThat(started).hasSize(3);

        engine.cancelProcessInstance(parent, "withdrawn");
        runJobs(context);

        assertThat(started).allSatisfy(child ->
                assertThat(engine.getProcessInstanceById(child).getStatus()).isEqualTo(ProcessStatus.CANCELLED));
        assertThat(engine.lineage(parent).children()).hasSize(3);
        assertThat(events(context, parent)).doesNotContain("FOR_EACH_COMPLETED");
    }

    @Test
    void leavingTheStepThroughATimeoutCancelsTheChildrenAndNothingStartsAfterwards() {
        AbadaEngine engine = context.getBean(AbadaEngine.class);
        deploy(context, """
                version: abada.io/v1
                metadata: { key: kyc_timed, name: KYC timed }
                flow:
                  entry: start
                  nodes:
                    - { id: start, type: webhook, next: check_each }
                    - id: check_each
                      type: for-each
                      items: "${documents}"
                      process: document_check
                      item_input: document
                      outputs: { verdict: verdict }
                      max_items: 10
                      max_parallel: 2
                      results: check_results
                      on_timeout: { after: PT1H, then: late }
                      next: done
                    - { id: late, type: human-input, assignees: [ops], next: done }
                    - { id: done, type: end }
                """);
        String parent = engine.startProcess(PROJECT, "kyc_timed", "alice", Map.of("documents", docs(6))).getId();
        List<String> started = running(parent);
        assertThat(started).hasSize(2);
        BoundaryMeta timeout = engine.getProcessInstanceById(parent).getDefinition().boundariesOf("check_each")
                .stream().filter(boundary -> boundary.kind() == BoundaryMeta.Kind.TIMEOUT).findFirst().orElseThrow();
        String token = engine.getProcessInstanceById(parent).getWaitingTokens().getFirst().id();

        assertThat(engine.fireTimeout(parent, "check_each", token, timeout.id())).isTrue();
        runJobs(context);

        assertThat(engine.getProcessInstanceById(parent).getActiveTokens()).containsExactly("late");
        assertThat(started).allSatisfy(child ->
                assertThat(engine.getProcessInstanceById(child).getStatus()).isEqualTo(ProcessStatus.CANCELLED));
        assertThat(engine.lineage(parent).children()).hasSize(2);
        assertThat(context.getBean(ForEachItemRepository.class)
                .findByParentInstanceIdAndParentTokenIdOrderByItemIndex(parent, token)).isEmpty();
    }

    @Test
    void withoutAnErrorRouteAFailedItemOpensAnIncidentAndRetryRunsTheWholeListAgain() {
        AbadaEngine engine = context.getBean(AbadaEngine.class);
        deploy(context, """
                version: abada.io/v1
                metadata: { key: kyc_strict, name: KYC strict }
                flow:
                  entry: start
                  nodes:
                    - { id: start, type: webhook, next: check_each }
                    - id: check_each
                      type: for-each
                      items: "${documents}"
                      process: document_check
                      item_input: document
                      outputs: { verdict: verdict }
                      max_items: 10
                      max_parallel: 5
                      results: check_results
                      next: done
                    - { id: done, type: end }
                """);
        String parent = engine.startProcess(PROJECT, "kyc_strict", "alice", Map.of("documents", docs(2))).getId();
        List<String> first = running(parent);
        engine.cancelProcessInstance(first.get(0), "stopped");
        completeItem(first.get(1), Map.of("verdict", "ok"));
        runJobs(context);

        IncidentEntity incident = context.getBean(IncidentRepository.class)
                .findByProcessInstanceIdAndResolvedAtIsNull(parent).stream().findFirst().orElseThrow();
        assertThat(incident.getType()).isEqualTo("CHILD_FAILED");
        assertThat(incident.getMessage()).contains("FOR_EACH_ITEM_FAILED");

        engine.retryIncident(parent, incident.getId());
        assertThat(engine.lineage(parent).children()).hasSize(4);
        for (String child : running(parent)) completeItem(child, Map.of("verdict", "ok"));
        runJobs(context);
        assertThat(engine.getProcessInstanceById(parent).isCompleted()).isTrue();
    }
}
