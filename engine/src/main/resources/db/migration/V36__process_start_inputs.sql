-- E15: the variables a root instance was started with, kept as evidence so a proposed change can be replayed
-- on real cases. Portable SQL (H2 and PostgreSQL). Governed by the project's evidence policy: the payload is
-- encrypted, masked when the policy is "redacted", absent when it is "none", and cleared after retention.
CREATE TABLE process_start_inputs (
    instance_id      VARCHAR(255) PRIMARY KEY,
    project_id       VARCHAR(255) NOT NULL,
    definition_key   VARCHAR(255) NOT NULL,
    deployment_id    VARCHAR(64)  NOT NULL,
    payload_mode     VARCHAR(16)  NOT NULL,
    inputs_enc       TEXT,
    redacted         BOOLEAN DEFAULT FALSE NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    purge_after      TIMESTAMP WITH TIME ZONE NOT NULL,
    purged_at        TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_start_inputs_deployment ON process_start_inputs (deployment_id, created_at);
CREATE INDEX idx_start_inputs_purge ON process_start_inputs (purge_after);
