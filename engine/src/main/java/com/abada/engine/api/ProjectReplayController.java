package com.abada.engine.api;

import com.abada.engine.core.replay.ReplayDiffService;
import com.abada.engine.core.replay.ReplayRunner;
import com.abada.engine.core.replay.ReplayService;
import com.abada.engine.persistence.entity.ProjectMemberEntity.Role;
import com.abada.engine.persistence.entity.ReplayCaseEntity;
import com.abada.engine.persistence.entity.ReplayRunEntity;
import com.abada.engine.project.ProjectAccessService;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replays of an Insight proposal on recorded cases (E15). A replay is evidence for a reviewer: what the proposed
 * definition would have done on the same inputs. It changes no instance and deploys nothing.
 */
@RestController
@RequestMapping("/v1/projects/{projectId}/insight/proposals/{proposalId}/replays")
public class ProjectReplayController {
    public record PlanRequest(Integer cases, BigDecimal budgetUsd) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CaseDto(long id, String originalInstanceId, String state, String reason, String stoppedAt,
            boolean inputsRedacted, Boolean originallyOverridden, Boolean changed, Double lastConfidence,
            List<String> toolsSkipped, BigDecimal costUsd, boolean costUnpriced, List<ReplayRunner.Step> originalPath,
            List<ReplayRunner.Step> replayPath, ReplayDiffService.CaseDiff diff) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunDto(String id, long proposalId, String status, int requestedCases, int withoutInputs,
            BigDecimal budgetUsd, BigDecimal estimatedCostUsd, boolean estimateUnpriced, BigDecimal costUsd,
            boolean costUnpriced, String requestedBy, Instant createdAt, Instant startedAt, Instant finishedAt,
            ReplayDiffService.Aggregate aggregate, List<CaseDto> cases) {}

    private final ReplayService replays;
    private final ProjectAccessService access;

    public ProjectReplayController(ReplayService replays, ProjectAccessService access) {
        this.replays = replays;
        this.access = access;
    }

    @PostMapping
    public ResponseEntity<RunDto> plan(@PathVariable String projectId, @PathVariable long proposalId,
            @RequestBody(required = false) PlanRequest request) {
        access.requireActive(projectId, Role.REVIEWER, Role.MAINTAINER, Role.OWNER);
        return ResponseEntity.ok(dto(replays.plan(projectId, proposalId, request == null ? null : request.cases(),
                request == null ? null : request.budgetUsd(), access.identity().username())));
    }

    @GetMapping
    public ResponseEntity<List<RunDto>> list(@PathVariable String projectId, @PathVariable long proposalId) {
        access.requireVisible(projectId);
        return ResponseEntity.ok(replays.forProposal(projectId, proposalId).stream()
                .map(run -> dto(run, null, null)).toList());
    }

    @GetMapping("/{runId}")
    public ResponseEntity<RunDto> get(@PathVariable String projectId, @PathVariable String runId) {
        access.requireVisible(projectId);
        return ResponseEntity.ok(dto(replays.get(projectId, runId)));
    }

    @PostMapping("/{runId}/start")
    public ResponseEntity<RunDto> start(@PathVariable String projectId, @PathVariable String runId) {
        access.requireActive(projectId, Role.REVIEWER, Role.MAINTAINER, Role.OWNER);
        return ResponseEntity.ok(dto(replays.start(projectId, runId, access.identity().username())));
    }

    @PostMapping("/{runId}/cancel")
    public ResponseEntity<RunDto> cancel(@PathVariable String projectId, @PathVariable String runId) {
        access.requireActive(projectId, Role.REVIEWER, Role.MAINTAINER, Role.OWNER);
        return ResponseEntity.ok(dto(replays.cancel(projectId, runId, access.identity().username())));
    }

    private RunDto dto(ReplayService.RunView view) {
        List<CaseDto> cases = view.cases().stream().map(this::dto).toList();
        return dto(view.run(), view.aggregate(), cases);
    }

    private static RunDto dto(ReplayRunEntity run, ReplayDiffService.Aggregate aggregate, List<CaseDto> cases) {
        return new RunDto(run.getId(), run.getProposalId(), run.getStatus().name(), run.getRequestedCases(),
                run.getWithoutInputs(), run.getBudgetUsd(), run.getEstimatedCostUsd(), run.isEstimateUnpriced(),
                run.getCostUsd(), run.isCostUnpriced(), run.getRequestedBy(), run.getCreatedAt(), run.getStartedAt(),
                run.getFinishedAt(), aggregate, cases);
    }

    private CaseDto dto(ReplayCaseEntity row) {
        return new CaseDto(row.getId(), row.getOriginalInstanceId(), row.getState().name(), row.getReason(),
                row.getStoppedAt(), row.isInputsRedacted(), row.getOriginallyOverridden(), row.getChanged(),
                row.getLastConfidence(), row.getToolsSkipped() == null ? List.of()
                        : Arrays.asList(row.getToolsSkipped().split(",")),
                row.getCostUsd(), row.isCostUnpriced(), replays.readPath(row.getOriginalPathJson()),
                replays.readPath(row.getReplayPathJson()), replays.readDiff(row.getDiffJson()));
    }
}
