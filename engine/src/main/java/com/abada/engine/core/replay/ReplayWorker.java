package com.abada.engine.core.replay;

import com.abada.engine.persistence.entity.ReplayCaseEntity;
import com.abada.engine.persistence.repository.ReplayRunRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the cases of replays that were started (E15), a few at once and outside any database transaction while
 * the model answers. A case is leased when claimed; a replica that dies leaves it to be claimed again after the
 * lease, so a case can be replayed twice but never lost.
 */
@Component
public class ReplayWorker {
    private static final Logger log = LoggerFactory.getLogger(ReplayWorker.class);

    private final ReplayService service;
    private final ReplayRunRepository runs;
    private final ModelAgentPort agents;
    private final ReplayProperties properties;
    private final String owner = "replay-" + UUID.randomUUID();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ExecutorService pool;

    public ReplayWorker(ReplayService service, ReplayRunRepository runs, ModelAgentPort agents,
            ReplayProperties properties) {
        this.service = service;
        this.runs = runs;
        this.agents = agents;
        this.properties = properties;
        this.pool = Executors.newFixedThreadPool(properties.getMaxParallel(), task -> {
            Thread thread = new Thread(task, "replay-case");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Scheduled(fixedDelayString = "${abada.replay.tick-ms:2000}", initialDelayString = "${abada.replay.tick-ms:2000}")
    public void tick() {
        tick(Instant.now());
    }

    /** One pass: stop runs over budget, then claim as many cases as there is room for. */
    void tick(Instant now) {
        if (!runs.existsByStatus(com.abada.engine.persistence.entity.ReplayRunEntity.Status.RUNNING)) return;
        service.enforceBudgets();
        int room = properties.getMaxParallel() - inFlight.get();
        if (room <= 0) return;
        List<ReplayCaseEntity> claimed = service.claim(room, owner);
        for (ReplayCaseEntity row : claimed) {
            inFlight.incrementAndGet();
            long id = row.getId();
            pool.submit(() -> {
                try {
                    service.execute(id, agents);
                } catch (RuntimeException failure) {
                    log.warn("replay case {} failed: {}", id, failure.getMessage());
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
    }

    /** Waits until nothing is running; for tests. */
    boolean idle() {
        return inFlight.get() == 0;
    }
}
