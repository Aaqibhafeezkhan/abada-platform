-- Keep usage statistics only: drop the columns that could point at a person or a network.
-- Country and region stay (coarse location); city, network owner, time zone, address hash and user agent go.
DROP INDEX IF EXISTS idx_install_events_ip;
ALTER TABLE install_events DROP COLUMN city;
ALTER TABLE install_events DROP COLUMN continent;
ALTER TABLE install_events DROP COLUMN timezone;
ALTER TABLE install_events DROP COLUMN asn;
ALTER TABLE install_events DROP COLUMN as_org;
ALTER TABLE install_events DROP COLUMN ip_hash;
ALTER TABLE install_events DROP COLUMN user_agent;
CREATE INDEX IF NOT EXISTS idx_install_events_install ON install_events (install_id, received_at);
