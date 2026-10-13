package com.abada.engine.core.replay;

import com.abada.engine.core.ProcessInstance;
import com.abada.engine.core.agent.AgentStepService;
import com.abada.engine.core.agent.EvidencePolicy;
import com.abada.engine.core.agent.EvidenceRedactor;
import com.abada.engine.persistence.entity.ProcessStartInputEntity;
import com.abada.engine.persistence.repository.ProcessStartInputRepository;
import com.abada.engine.persistence.repository.ProjectRepository;
import com.abada.engine.security.AesEncryption;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Keeps the variables a root instance started with, so a proposed change can later be replayed on real cases
 * (E15). The project's evidence policy decides what is kept: nothing under {@code none}, masked values under
 * {@code redacted}, the values as sent under {@code full}; always encrypted, always cleared after retention.
 */
@Service
public class StartInputService {
    /** What a replay can use of an instance's start inputs. */
    public record Inputs(Map<String, Object> variables, boolean redacted) {}

    private final ProcessStartInputRepository inputs;
    private final ProjectRepository projects;
    private final AesEncryption encryption;
    private final ObjectMapper json;

    public StartInputService(ProcessStartInputRepository inputs, ProjectRepository projects,
            AesEncryption encryption, ObjectMapper json) {
        this.inputs = inputs;
        this.projects = projects;
        this.encryption = encryption;
        this.json = json;
    }

    /** Records the start inputs of a root instance in the caller's transaction. */
    public void record(ProcessInstance instance, String deploymentId, Map<String, Object> initial, Instant now) {
        EvidencePolicy policy = projects.findById(instance.getProjectId())
                .map(row -> new EvidencePolicy(EvidencePolicy.Mode.fromWire(row.getEvidencePayloads()),
                        row.getEvidenceRetentionDays()))
                .orElse(EvidencePolicy.DEFAULT);
        ProcessStartInputEntity row = new ProcessStartInputEntity();
        row.setInstanceId(instance.getId());
        row.setProjectId(instance.getProjectId());
        row.setDefinitionKey(instance.getDefinition().getId());
        row.setDeploymentId(deploymentId);
        row.setPayloadMode(policy.payloads().wireName());
        row.setCreatedAt(now);
        row.setPurgeAfter(now.plus(policy.retentionDays(), ChronoUnit.DAYS));
        if (policy.payloads() != EvidencePolicy.Mode.NONE && initial != null && !initial.isEmpty()) {
            JsonNode original = json.valueToTree(initial);
            JsonNode kept = original;
            if (policy.payloads() == EvidencePolicy.Mode.REDACTED) {
                Map<String, Object> sensitive = new LinkedHashMap<>();
                for (String name : instance.getDefinition().getSensitiveVariables()) {
                    sensitive.put(name, initial.get(name));
                }
                kept = new EvidenceRedactor(sensitive).redact(original);
                row.setRedacted(!kept.equals(original));
            }
            row.setInputsEnc(encryption.encrypt(AgentStepService.canonicalJson(kept)));
        } else if (policy.payloads() != EvidencePolicy.Mode.NONE) {
            row.setInputsEnc(encryption.encrypt("{}"));
        }
        inputs.save(row);
    }

    /** The inputs a replay may use, or empty when none were kept (policy {@code none}, purged, or never recorded). */
    public Optional<Inputs> find(String instanceId) {
        return inputs.findById(instanceId).filter(row -> row.getInputsEnc() != null).map(row -> {
            try {
                Map<String, Object> variables = json.readValue(encryption.decrypt(row.getInputsEnc()),
                        new TypeReference<LinkedHashMap<String, Object>>() {});
                return new Inputs(variables, row.isRedacted());
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Start inputs of " + instanceId + " cannot be read", exception);
            }
        });
    }
}
