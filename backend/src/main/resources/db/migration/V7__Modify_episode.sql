-- Step 1: Disable foreign key enforcement to allow dropping the original table.
-- PRAGMA foreign_keys=off;

-- Step 2: Create the new table with the desired schema.
CREATE TABLE episode_new
(
    id                TEXT      PRIMARY KEY NOT NULL,
    channel_id        TEXT      NULL,
    title             TEXT      NOT NULL,
    description       TEXT,
    published_at      TIMESTAMP,
    default_cover_url TEXT,
    max_cover_url     TEXT,
    duration          TEXT,
    download_status   TEXT      NOT NULL,
    audio_file_path   TEXT,
    created_at        TIMESTAMP,
    error_log         TEXT      DEFAULT NULL
);

-- Step 3: Copy all data from the old table to the new table.
INSERT INTO episode_new (id, channel_id, title, description, published_at, default_cover_url, max_cover_url, duration, download_status, audio_file_path, created_at, error_log)
SELECT id, channel_id, title, description, published_at, default_cover_url, max_cover_url, duration, download_status, audio_file_path, created_at, error_log
FROM episode;

-- Step 4: Remove the original table.
DROP TABLE episode;

-- Step 5: Rename the new table to the original table name.
ALTER TABLE episode_new RENAME TO episode;

-- Step 6: Re-enable foreign key enforcement for subsequent operations.
-- PRAGMA foreign_keys=on;