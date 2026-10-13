package com.abada.engine.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The variables a root instance was started with (E15), kept under the project's evidence policy so a proposed
 * definition can be replayed on real cases. The payload is encrypted, absent under {@code none}, and cleared
 * once {@code purge_after} passes.
 */
@Entity
@Table(name = "process_start_inputs")
public class ProcessStartInputEntity {
    @Id
    @Column(name = "instance_id")
    private String instanceId;

    @Column(name = "project_id")
    private String projectId;

    @Column(name = "definition_key")
    private String definitionKey;

    @Column(name = "deployment_id")
    private String deploymentId;

    @Column(name = "payload_mode")
    private String payloadMode;

    @Column(name = "inputs_enc", columnDefinition = "TEXT")
    private String inputsEnc;

    private boolean redacted;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "purge_after")
    private Instant purgeAfter;

    @Column(name = "purged_at")
    private Instant purgedAt;

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getDefinitionKey() { return definitionKey; }
    public void setDefinitionKey(String definitionKey) { this.definitionKey = definitionKey; }
    public String getDeploymentId() { return deploymentId; }
    public void setDeploymentId(String deploymentId) { this.deploymentId = deploymentId; }
    public String getPayloadMode() { return payloadMode; }
    public void setPayloadMode(String payloadMode) { this.payloadMode = payloadMode; }
    public String getInputsEnc() { return inputsEnc; }
    public void setInputsEnc(String inputsEnc) { this.inputsEnc = inputsEnc; }
    public boolean isRedacted() { return redacted; }
    public void setRedacted(boolean redacted) { this.redacted = redacted; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getPurgeAfter() { return purgeAfter; }
    public void setPurgeAfter(Instant purgeAfter) { this.purgeAfter = purgeAfter; }
    public Instant getPurgedAt() { return purgedAt; }
    public void setPurgedAt(Instant purgedAt) { this.purgedAt = purgedAt; }
}
