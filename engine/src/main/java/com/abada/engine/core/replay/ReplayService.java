package com.abada.engine.core.replay;

import com.abada.engine.api.ApiErrorCode;
import com.abada.engine.api.ApiException;
import com.abada.engine.core.ActivityHistoryService;
import com.abada.engine.core.agent.AgentCostService;
import com.abada.engine.core.model.ParsedProcessDefinition;
import com.abada.engine.core.model.ReviewedByMeta;
import com.abada.engine.core.replay.ReplayRunner.Step;
import com.abada.engine.parser.AplParser;
import com.abada.engine.persistence.entity.InsightProposalEntity;
import com.abada.engine.persistence.entity.ProcessInstanceEntity;
import com.abada.engine.persistence.entity.ReplayCaseEntity;
import com.abada.engine.persistence.entity.ReplayRunEntity;
import com.abada.engine.persistence.repository.InsightProposalRepository;
import com.abada.engine.persistence.repository.ProcessInstanceRepository;
import com.abada.engine.persistence.repository.ReplayCaseRepository;
import com.abada.engine.persistence.repository.ReplayRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replays an Insight proposal on the last recorded cases of the deployment it targets (E15). A replay is
 * evidence for a reviewer, not a verdict: it shows what the proposed definition would have done on the same
 * inputs, one model call per agent step, with nothing written to any instance. State lives in
 * {@code replay_runs} and {@code replay_cases}, so a restart resumes.
 */
@Service
public class ReplayService {
    private static final Set<InsightProposalEntity.Status> OPEN = Set.of(InsightProposalEntity.Status.DRAFT,
            InsightProposalEntity.Status.IN_REVIEW);
    private static final Set<ReplayRunEntity.Status> ACTIVE = Set.of(ReplayRunEntity.Status.PLANNED,
            ReplayRunEntity.Status.RUNNING);
    /** A run that can qualify a proposal for approval: it ran to the end of its cases. */
    static final Set<ReplayRunEntity.Status> QUALIFYING = Set.of(ReplayRunEntity.Status.COMPLETED);

    public record RunView(ReplayRunEntity run, List<ReplayCaseEntity> cases, ReplayDiffService.Aggregate aggregate) {}

    private final InsightProposalRepository proposals;
    private final ProcessInstanceRepository instances;
    private final ReplayRunRepository runs;
    private final ReplayCaseRepository cases;
    private final StartInputService startInputs;
    private final OriginalPathReader originalPaths;
    private final ReplayDiffService diffs = new ReplayDiffService();
    private final AgentCostService costs;
    private final ActivityHistoryService history;
    private final AplParser parser;
    private final ReplayProperties properties;
    private final ObjectMapper json;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public ReplayService(InsightProposalRepository proposals, ProcessInstanceRepository instances,
            ReplayRunRepository runs, ReplayCaseRepository cases, StartInputService startInputs,
            OriginalPathReader originalPaths, AgentCostService costs, ActivityHistoryService history,
            AplParser parser, ReplayProperties properties, ObjectMapper json,
            org.springframework.transaction.support.TransactionTemplate transactions) {
        this.transactions = transactions;
        this.proposals = proposals;
        this.instances = instances;
        this.runs = runs;
        this.cases = cases;
        this.startInputs = startInputs;
        this.originalPaths = originalPaths;
        this.costs = costs;
        this.history = history;
        this.parser = parser;
        this.properties = properties;
        this.json = json;
    }

    /** SHA-256 of the proposed source: what a replay qualifies. Editing the proposal invalidates its runs. */
    public static String checksum(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Selects the cases and prices the run from what they cost originally; nothing is spent yet. */
    @Transactional
    public RunView plan(String projectId, long proposalId, Integer requestedCases, BigDecimal budgetUsd,
            String actor) {
        InsightProposalEntity proposal = proposals.findByIdAndProjectId(proposalId, projectId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Proposal not found"));
        if (!OPEN.contains(proposal.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.ENGINE_COMMAND_REJECTED,
                    "Only a proposal under review can be replayed");
        }
        if (runs.existsByProposalIdAndStatusIn(proposalId, ACTIVE)) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.ENGINE_COMMAND_REJECTED,
                    "This proposal already has a replay in progress");
        }
        int wanted = requestedCases == null ? properties.getDefaultCases() : requestedCases;
        if (wanted < 1 || wanted > properties.getMaxCases()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_REQUEST,
                    "cases must be between 1 and " + properties.getMaxCases());
        }
        if (budgetUsd != null && budgetUsd.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_REQUEST, "budgetUsd must be positive");
        }
        // The proposal must be a definition the engine accepts; a source that does not parse cannot be replayed.
        parser.parseDetailed(proposal.getProposedSource().getBytes(StandardCharsets.UTF_8));

        List<ProcessInstanceEntity> selected = instances.findReplayable(proposal.getDefinitionDeploymentId(),
                PageRequest.of(0, wanted));
        var original = costs.costs(selected.stream().map(ProcessInstanceEntity::getId).toList());
        BigDecimal estimate = BigDecimal.ZERO;
        boolean unpriced = false;
        for (var cost : original.values()) {
            if (cost.usd() != null) estimate = estimate.add(cost.usd());
            unpriced |= cost.includesUnpriced();
        }
        Instant now = Instant.now();
        ReplayRunEntity run = new ReplayRunEntity();
        run.setProjectId(projectId);
        run.setProposalId(proposalId);
        run.setProposalChecksum(checksum(proposal.getProposedSource()));
        run.setDefinitionKey(proposal.getDefinitionKey());
        run.setBaseDeploymentId(proposal.getDefinitionDeploymentId());
        run.setRequestedCases(wanted);
        run.setWithoutInputs((int) instances.countNotReplayable(proposal.getDefinitionDeploymentId()));
        run.setBudgetUsd(budgetUsd);
        run.setEstimatedCostUsd(estimate);
        run.setEstimateUnpriced(unpriced);
        run.setRequestedBy(actor);
        run.setPurgeAfter(now.plus(properties.getRetentionDays(), ChronoUnit.DAYS));
        runs.save(run);
        for (ProcessInstanceEntity instance : selected) {
            ReplayCaseEntity row = new ReplayCaseEntity();
            row.setRunId(run.getId());
            row.setOriginalInstanceId(instance.getId());
            cases.save(row);
        }
        history.record("REPLAY_PLANNED", null, proposal.getDefinitionKey(), null, Map.of(
                "runId", run.getId(), "proposalId", proposalId, "cases", selected.size(), "actor", actor));
        return view(run);
    }

    @Transactional
    public RunView start(String projectId, String runId, String actor) {
        ReplayRunEntity run = find(projectId, runId);
        if (run.getStatus() != ReplayRunEntity.Status.PLANNED) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.ENGINE_COMMAND_REJECTED,
                    "Only a planned replay can be started");
        }
        long pending = cases.countByRunIdAndStateIn(runId, Set.of(ReplayCaseEntity.State.PENDING));
        run.setStartedAt(Instant.now());
        if (pending == 0) {
            run.setStatus(ReplayRunEntity.Status.FAILED);
            run.setFinishedAt(Instant.now());
        } else {
            run.setStatus(ReplayRunEntity.Status.RUNNING);
        }
        runs.save(run);
        history.record("REPLAY_STARTED", null, run.getDefinitionKey(), null,
                Map.of("runId", runId, "proposalId", run.getProposalId(), "cases", pending, "actor", actor));
        return view(run);
    }

    @Transactional
    public RunView cancel(String projectId, String runId, String actor) {
        ReplayRunEntity run = find(projectId, runId);
        if (!ACTIVE.contains(run.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.ENGINE_COMMAND_REJECTED,
                    "The replay has already finished");
        }
        for (ReplayCaseEntity row : cases.findByRunIdOrderById(runId)) {
            if (row.getState() == ReplayCaseEntity.State.PENDING || row.getState() == ReplayCaseEntity.State.RUNNING) {
                skip(row, "CANCELLED", "the replay was cancelled");
                cases.save(row);
            }
        }
        run.setStatus(ReplayRunEntity.Status.CANCELLED);
        run.setFinishedAt(Instant.now());
        runs.save(run);
        history.record("REPLAY_CANCELLED", null, run.getDefinitionKey(), null,
                Map.of("runId", runId, "proposalId", run.getProposalId(), "actor", actor));
        return view(run);
    }

    @Transactional(readOnly = true)
    public RunView get(String projectId, String runId) {
        return view(find(projectId, runId));
    }

    @Transactional(readOnly = true)
    public List<ReplayRunEntity> forProposal(String projectId, long proposalId) {
        return runs.findByProjectIdAndProposalIdOrderByCreatedAtDesc(projectId, proposalId);
    }

    /**
     * The run that lets a proposal be approved under a replay-required policy: finished, for the proposal's
     * current source, with at least {@code minCases} cases actually replayed.
     */
    @Transactional(readOnly = true)
    public Optional<ReplayRunEntity> qualifying(InsightProposalEntity proposal, int minCases) {
        String checksum = checksum(proposal.getProposedSource());
        return runs.findByProposalIdAndProposalChecksumAndStatusInOrderByCreatedAtDesc(proposal.getId(), checksum,
                QUALIFYING).stream().filter(run -> replayed(run.getId()) >= minCases).findFirst();
    }

    private long replayed(String runId) {
        return cases.findByRunIdOrderById(runId).stream().filter(row -> switch (row.getState()) {
            case DONE, NEEDS_HUMAN, NEEDS_EXTERNAL -> true;
            default -> false;
        }).count();
    }

    // ---- execution (called by ReplayWorker) ----

    /** Marks the case running under a lease; false when another replica got it first or the run stopped. */
    @Transactional
    public List<ReplayCaseEntity> claim(int limit, String owner) {
        Instant now = Instant.now();
        List<ReplayCaseEntity> claimed = cases.claimable(now, limit);
        for (ReplayCaseEntity row : claimed) {
            row.setState(ReplayCaseEntity.State.RUNNING);
            row.setLeaseOwner(owner);
            row.setLeaseUntil(now.plusSeconds(properties.getLeaseSeconds()));
            if (row.getStartedAt() == null) row.setStartedAt(now);
        }
        return cases.saveAll(claimed);
    }

    /** Stops runs that spent their budget: their remaining cases are skipped and the run is partial. */
    @Transactional
    public void enforceBudgets() {
        for (ReplayRunEntity run : runs.findByStatus(ReplayRunEntity.Status.RUNNING)) {
            if (run.getBudgetUsd() == null || run.getCostUsd().compareTo(run.getBudgetUsd()) < 0) continue;
            for (ReplayCaseEntity row : cases.findByRunIdOrderById(run.getId())) {
                if (row.getState() == ReplayCaseEntity.State.PENDING) {
                    skip(row, "BUDGET", "the replay's budget was spent");
                    cases.save(row);
                }
            }
            settle(run);
        }
    }

    /** Runs one claimed case outside any transaction, then records the outcome. */
    public void execute(long caseId, ReplayRunner.AgentPort agents) {
        ReplayCaseEntity row = cases.findById(caseId).orElse(null);
        if (row == null || row.getState() != ReplayCaseEntity.State.RUNNING) return;
        ReplayRunEntity run = runs.findById(row.getRunId()).orElse(null);
        if (run == null || run.getStatus() != ReplayRunEntity.Status.RUNNING) return;
        Outcome outcome;
        try {
            outcome = compute(run, row, agents);
        } catch (RuntimeException failure) {
            outcome = Outcome.failed(failure.getMessage() == null ? failure.getClass().getSimpleName()
                    : failure.getMessage());
        }
        Outcome settled = outcome;
        transactions.executeWithoutResult(status -> record(caseId, settled));
    }

    private record Outcome(ReplayCaseEntity.State state, String reason, String stoppedAt, boolean redacted,
            Boolean overridden, List<Step> original, List<Step> replayed, ReplayDiffService.CaseDiff diff,
            Double confidence, Set<String> toolsSkipped, BigDecimal cost, boolean unpriced) {
        static Outcome failed(String reason) {
            return new Outcome(ReplayCaseEntity.State.FAILED, reason, null, false, null, null, null, null, null,
                    Set.of(), BigDecimal.ZERO, false);
        }

        static Outcome skipped(String reason) {
            return new Outcome(ReplayCaseEntity.State.SKIPPED, reason, null, false, null, null, null, null, null,
                    Set.of(), BigDecimal.ZERO, false);
        }
    }

    private Outcome compute(ReplayRunEntity run, ReplayCaseEntity row, ReplayRunner.AgentPort agents) {
        Optional<StartInputService.Inputs> inputs = startInputs.find(row.getOriginalInstanceId());
        if (inputs.isEmpty()) return Outcome.skipped("PAYLOAD_PURGED");
        InsightProposalEntity proposal = proposals.findById(run.getProposalId()).orElse(null);
        if (proposal == null || !ReplayService.checksum(proposal.getProposedSource()).equals(run.getProposalChecksum())) {
            return Outcome.skipped("PROPOSAL_CHANGED");
        }
        ParsedProcessDefinition definition = parser.parseDetailed(
                proposal.getProposedSource().getBytes(StandardCharsets.UTF_8)).definition();
        List<Step> original = originalPaths.read(row.getOriginalInstanceId());
        ReplayRunner.Result result = new ReplayRunner().run(definition, inputs.get().variables(), agents,
                OriginalPathReader.decisionsOf(original));
        boolean overridden = definition.getReviews().values().stream()
                .anyMatch(review -> ReplayDiffService.overridden(original, review.reviewNode(), review.accept()));
        ReplayCaseEntity.State state = switch (result.end()) {
            case COMPLETED -> ReplayCaseEntity.State.DONE;
            case NEEDS_HUMAN -> ReplayCaseEntity.State.NEEDS_HUMAN;
            case NEEDS_EXTERNAL -> ReplayCaseEntity.State.NEEDS_EXTERNAL;
            case FAILED -> ReplayCaseEntity.State.FAILED;
        };
        return new Outcome(state, result.reason(), result.stoppedAt(), inputs.get().redacted(), overridden, original,
                result.path(), diffs.compare(original, result.path()), result.lastConfidence(), result.toolsSkipped(),
                result.costUsd(), result.costUnpriced());
    }

    private void record(long caseId, Outcome outcome) {
        ReplayCaseEntity row = cases.findById(caseId).orElseThrow();
        ReplayRunEntity run = runs.findById(row.getRunId()).orElseThrow();
        // A cancelled or budget-stopped run keeps what it already settled.
        if (row.getState() != ReplayCaseEntity.State.RUNNING) return;
        row.setState(outcome.state());
        row.setReason(outcome.reason());
        row.setStoppedAt(outcome.stoppedAt());
        row.setInputsRedacted(outcome.redacted());
        row.setOriginallyOverridden(outcome.overridden());
        row.setCostUsd(outcome.cost());
        row.setCostUnpriced(outcome.unpriced());
        row.setLastConfidence(outcome.confidence());
        row.setToolsSkipped(outcome.toolsSkipped().isEmpty() ? null : String.join(",", outcome.toolsSkipped()));
        if (outcome.original() != null) {
            row.setOriginalPathJson(write(outcome.original()));
            row.setReplayPathJson(write(outcome.replayed()));
            row.setDiffJson(write(outcome.diff()));
            row.setChanged(outcome.diff().changed());
        }
        row.setFinishedAt(Instant.now());
        row.setLeaseOwner(null);
        row.setLeaseUntil(null);
        cases.save(row);
        if (outcome.cost() != null) run.setCostUsd(run.getCostUsd().add(outcome.cost()));
        run.setCostUnpriced(run.isCostUnpriced() || outcome.unpriced());
        runs.save(run);
        settle(run);
    }

    /** Finishes the run once none of its cases is waiting or running. */
    private void settle(ReplayRunEntity run) {
        if (run.getStatus() != ReplayRunEntity.Status.RUNNING) return;
        long open = cases.countByRunIdAndStateIn(run.getId(),
                Set.of(ReplayCaseEntity.State.PENDING, ReplayCaseEntity.State.RUNNING));
        if (open > 0) return;
        boolean budgetStopped = cases.findByRunIdOrderById(run.getId()).stream()
                .anyMatch(row -> row.getState() == ReplayCaseEntity.State.SKIPPED && "BUDGET".equals(row.getStoppedAt()));
        run.setStatus(budgetStopped ? ReplayRunEntity.Status.PARTIAL : ReplayRunEntity.Status.COMPLETED);
        run.setFinishedAt(Instant.now());
        runs.save(run);
        history.record("REPLAY_COMPLETED", null, run.getDefinitionKey(), null, Map.of("runId", run.getId(),
                "proposalId", run.getProposalId(), "status", run.getStatus().name(),
                "costUsd", run.getCostUsd().toPlainString()));
    }

    private static void skip(ReplayCaseEntity row, String code, String reason) {
        row.setState(ReplayCaseEntity.State.SKIPPED);
        row.setStoppedAt(code);
        row.setReason(reason);
        row.setFinishedAt(Instant.now());
        row.setLeaseOwner(null);
        row.setLeaseUntil(null);
    }

    private ReplayRunEntity find(String projectId, String runId) {
        return runs.findByIdAndProjectId(runId, projectId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND, "Replay not found"));
    }

    private RunView view(ReplayRunEntity run) {
        List<ReplayCaseEntity> rows = cases.findByRunIdOrderById(run.getId());
        return new RunView(run, rows, aggregate(rows));
    }

    private ReplayDiffService.Aggregate aggregate(List<ReplayCaseEntity> rows) {
        int unchanged = 0;
        int changed = 0;
        int needsHuman = 0;
        int needsExternal = 0;
        int failed = 0;
        int skipped = 0;
        int overridden = 0;
        int overriddenDiffering = 0;
        for (ReplayCaseEntity row : rows) {
            switch (row.getState()) {
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
                case NEEDS_HUMAN -> needsHuman++;
                case NEEDS_EXTERNAL -> needsExternal++;
                default -> { }
            }
            if (row.getState() == ReplayCaseEntity.State.DONE || row.getState() == ReplayCaseEntity.State.NEEDS_HUMAN
                    || row.getState() == ReplayCaseEntity.State.NEEDS_EXTERNAL) {
                if (Boolean.TRUE.equals(row.getChanged())) changed++;
                else unchanged++;
            }
            if (Boolean.TRUE.equals(row.getOriginallyOverridden())) {
                overridden++;
                if (Boolean.TRUE.equals(row.getChanged())) overriddenDiffering++;
            }
        }
        return new ReplayDiffService.Aggregate(rows.size(), unchanged, changed, needsHuman, needsExternal, failed,
                skipped, overridden, overriddenDiffering);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** The recorded paths of a case, for the API. */
    public List<Step> readPath(String pathJson) {
        if (pathJson == null) return List.of();
        try {
            return json.readValue(pathJson, new TypeReference<List<Step>>() {});
        } catch (java.io.IOException exception) {
            return List.of();
        }
    }

    public ReplayDiffService.CaseDiff readDiff(String diffJson) {
        if (diffJson == null) return null;
        try {
            return json.readValue(diffJson, ReplayDiffService.CaseDiff.class);
        } catch (java.io.IOException exception) {
            return null;
        }
    }
}
