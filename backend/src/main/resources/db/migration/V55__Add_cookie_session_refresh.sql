-- Cookie session lifecycle metadata.
-- YouTube rotates account cookies continuously, so a stored cookies.txt is a snapshot that
-- goes stale within hours. These columns let the backend own the session: track whether it
-- is still valid, when it was last refreshed and when the next refresh is due.

ALTER TABLE cookie_config ADD COLUMN session_status TEXT NOT NULL DEFAULT 'UNKNOWN';

ALTER TABLE cookie_config ADD COLUMN auto_refresh_enabled INTEGER NOT NULL DEFAULT 1;

ALTER TABLE cookie_config ADD COLUMN rotate_interval_seconds INTEGER NOT NULL DEFAULT 600;

ALTER TABLE cookie_config ADD COLUMN last_rotated_at TIMESTAMP NULL;

-- Left NULL on purpose: the scheduler treats NULL as "due now", so an upgraded
-- installation attempts its first refresh on the next scan.
ALTER TABLE cookie_config ADD COLUMN next_rotate_at TIMESTAMP NULL;

ALTER TABLE cookie_config ADD COLUMN last_checked_at TIMESTAMP NULL;

ALTER TABLE cookie_config ADD COLUMN rotate_failure_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE cookie_config ADD COLUMN last_failure_reason TEXT NULL;
