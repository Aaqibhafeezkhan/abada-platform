-- E16: who deployed a definition version and, for a rollback, which version it restores.
-- Portable SQL (H2 and PostgreSQL). Existing versions keep NULLs: their deployer
-- was recorded in history only.
ALTER TABLE process_definitions ADD COLUMN rollback_of INT;
ALTER TABLE process_definitions ADD COLUMN deployed_by VARCHAR(255);
ALTER TABLE process_definitions ADD COLUMN deploy_comment VARCHAR(1000);
