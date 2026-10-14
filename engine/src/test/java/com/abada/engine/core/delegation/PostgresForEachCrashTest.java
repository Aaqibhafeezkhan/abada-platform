package com.abada.engine.core.delegation;

import static com.abada.engine.core.delegation.PostgresCallProcessTest.PROJECT;
import static com.abada.engine.core.delegation.PostgresCallProcessTest.deployFixture;
import static com.abada.engine.core.delegation.PostgresCallProcessTest.events;
import static com.abada.engine.core.delegation.PostgresCallProcessTest.runJobs;
import static com.abada.engine.core.delegation.PostgresCallProcessTest.startApplication;
import static org.assertj.core.api.Assertions.assertThat;

import com.abada.engine.core.AbadaEngine;
import com.abada.engine.core.ExternalTaskCommandService;
import com.abada.engine.core.model.ProcessStatus;
import com.abada.engine.dto.FetchAndLockRequest;
import com.abada.engine.dto.LineageDto;
import com.abada.engine.persistence.entity.ForEachItemEntity;
import com.abada.engine.persistence.repository.ForEachItemRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The exit-demo core of E21: the engine stops with items in every state and, restarted, every item has exactly
 * one child, the results are gathered once, and two replicas never run more than {@code max_parallel} at once.
 */
@Testcontainers
class PostgresForEachCrashTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("abada_for_each_crash").withUsername("abada").withPassword("abada");

    private static ConfigurableApplicationContext context;

    @BeforeAll
    static void start() {
        context = startApplication(POSTGRES, false);
        deployFixture(context, "for-each-item.apl.yaml");
        deployFixture(context, "for-each-batch.apl.yaml");
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
    }

    @Test
    void restartsWithItemsInEveryStateGiveOneChildPerItemAndOneResult() {
        List<String> documents = List.of("d0", "d1", "d2", "d3", "d4", "d5", "d6");
        String parent = context.getBean(AbadaEngine.class).startProcess(PROJECT, "kyc_batch", "alice",
                Map.of("case_id", "crash", "documents", documents)).getId();

        // Two children finish (one resumed, one ended but not yet resumed), one is running, four are pending.
        List<String> first = running(parent);
        completeItem(context, first.get(0), "ok");
        runJobs(context);
        completeItem(context, first.get(1), "ok");
        assertThat(items(parent, ForEachItemEntity.State.PENDING)).isNotEmpty();

        restart();
        int rounds = 0;
        while (isWaiting(parent) && rounds++ < 40) {
            runJobs(context);
            for (String child : running(parent)) completeItem(context, child, "ok");
            runJobs(context);
            if (rounds == 3) restart();   // and again mid-way
        }

        AbadaEngine engine = context.getBean(AbadaEngine.class);
        assertThat(engine.getProcessInstanceById(parent).getActiveTokens()).containsExactly("decide");
        List<LineageDto.Link> children = engine.lineage(parent).children();
        assertThat(children).hasSize(7);
        assertThat(children.stream().map(link -> String.valueOf(engine.getProcessInstanceById(link.instanceId())
                .getVariables().get("document"))).sorted().toList()).containsExactlyElementsOf(documents);
        assertThat(children).allSatisfy(link -> assertThat(engine.getProcessInstanceById(link.instanceId()).getStatus())
                .isEqualTo(ProcessStatus.COMPLETED));
        assertThat(events(context, parent).stream().filter("FOR_EACH_COMPLETED"::equals)).hasSize(1);
        assertThat(events(context, parent).stream().filter("FOR_EACH_ITEM_DONE"::equals)).hasSize(7);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) engine.getProcessInstanceById(parent)
                .getVariables().get("check_results");
        assertThat(results).hasSize(7).allSatisfy(entry -> assertThat(entry).containsEntry("ok", true));
    }

    @Test
    void twoReplicasFinishingChildrenAtOnceNeverRunMoreThanMaxParallelAndGatherOnce() throws Exception {
        List<String> documents = new ArrayList<>();
        for (int i = 0; i < 9; i++) documents.add("r" + i);
        String parent = context.getBean(AbadaEngine.class).startProcess(PROJECT, "kyc_batch", "alice",
                Map.of("case_id", "race", "documents", documents)).getId();
        ConfigurableApplicationContext replica = startApplication(POSTGRES, false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            int rounds = 0;
            while (isWaiting(parent) && rounds++ < 40) {
                List<String> now = running(parent);
                assertThat(now.size()).isLessThanOrEqualTo(3);
                for (String child : now) completeItem(context, child, "ok");
                CountDownLatch go = new CountDownLatch(1);
                List<Future<?>> runs = List.of(
                        pool.submit(() -> { go.await(); runJobs(context); return null; }),
                        pool.submit(() -> { go.await(); runJobs(replica); return null; }));
                go.countDown();
                for (Future<?> run : runs) run.get();
            }
        } finally {
            pool.shutdown();
            replica.close();
        }
        AbadaEngine engine = context.getBean(AbadaEngine.class);
        assertThat(engine.getProcessInstanceById(parent).getActiveTokens()).containsExactly("decide");
        assertThat(engine.lineage(parent).children()).hasSize(9);
        assertThat(events(context, parent).stream().filter("FOR_EACH_COMPLETED"::equals)).hasSize(1);
    }

    private static boolean isWaiting(String parent) {
        return context.getBean(AbadaEngine.class).getProcessInstanceById(parent).getActiveTokens()
                .contains("check_each");
    }

    private static List<String> running(String parent) {
        AbadaEngine engine = context.getBean(AbadaEngine.class);
        return engine.lineage(parent).children().stream().map(LineageDto.Link::instanceId)
                .filter(id -> engine.getProcessInstanceById(id).getStatus() == ProcessStatus.RUNNING).toList();
    }

    private static List<ForEachItemEntity> items(String parent, ForEachItemEntity.State state) {
        return context.getBean(ForEachItemRepository.class).findAll().stream()
                .filter(row -> row.getParentInstanceId().equals(parent) && row.getState() == state).toList();
    }

    private static void restart() {
        context.close();
        context = startApplication(POSTGRES, false);
    }

    private static void completeItem(ConfigurableApplicationContext app, String child, String verdict) {
        var workers = app.getBean(ExternalTaskCommandService.class);
        workers.fetchAndLock(new FetchAndLockRequest("w", List.of("doc.check"), 600_000L, 50));
        var task = app.getBean(com.abada.engine.persistence.repository.ExternalTaskRepository.class).findAll()
                .stream().filter(row -> row.getProcessInstanceId().equals(child)
                        && "doc.check".equals(row.getTopicName()) && "w".equals(row.getWorkerId())
                        && row.getStatus() == com.abada.engine.persistence.entity.ExternalTaskEntity.Status.LOCKED)
                .findFirst().orElseThrow(() -> new AssertionError("no open check for " + child));
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("verdict", verdict);
        workers.complete(task.getId(), "w", variables);
    }
}
