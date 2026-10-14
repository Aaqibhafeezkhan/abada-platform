package com.abada.engine.api;

import com.abada.engine.core.versioning.DefinitionVersionService;
import com.abada.engine.dto.ProcessDefinitionDto;
import com.abada.engine.persistence.entity.ProjectMemberEntity.Role;
import com.abada.engine.project.ProjectAccessService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Version history, source diff and rollback of a process (E16). */
@RestController
@RequestMapping("/v1/projects/{projectId}/processes/{processKey}/versions")
public class ProjectDefinitionVersionController {
    public record RollbackRequest(String comment, Integer expectedLatest) {}

    private final DefinitionVersionService versions;
    private final ProjectAccessService access;

    public ProjectDefinitionVersionController(DefinitionVersionService versions, ProjectAccessService access) {
        this.versions = versions;
        this.access = access;
    }

    @GetMapping
    public ResponseEntity<List<DefinitionVersionService.Version>> list(@PathVariable String projectId,
            @PathVariable String processKey) {
        access.require(projectId, Role.VIEWER, Role.MAINTAINER, Role.OPERATOR, Role.REVIEWER, Role.OWNER);
        return ResponseEntity.ok(versions.versions(projectId, processKey));
    }

    @GetMapping("/{from}/diff/{to}")
    public ResponseEntity<DefinitionVersionService.Diff> diff(@PathVariable String projectId,
            @PathVariable String processKey, @PathVariable int from, @PathVariable int to) {
        access.require(projectId, Role.VIEWER, Role.MAINTAINER, Role.OPERATOR, Role.REVIEWER, Role.OWNER);
        return ResponseEntity.ok(versions.diff(projectId, processKey, from, to));
    }

    /** Deploys an earlier version's source as the next version; instances already running are untouched. */
    @PostMapping("/{version}/rollback")
    public ResponseEntity<ProcessDefinitionDto> rollback(@PathVariable String projectId,
            @PathVariable String processKey, @PathVariable int version, @RequestBody RollbackRequest request) {
        access.requireActive(projectId, Role.OPERATOR, Role.MAINTAINER, Role.OWNER);
        return ResponseEntity.ok(ProcessDefinitionDto.deployed(versions.rollback(projectId, processKey, version,
                request == null ? null : request.comment(), request == null ? null : request.expectedLatest())));
    }
}
