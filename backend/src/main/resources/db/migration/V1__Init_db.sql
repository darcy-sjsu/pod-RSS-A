CREATE TABLE IF NOT EXISTS user
(
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    username        TEXT                                NOT NULL,
    password        TEXT                                NOT NULL,
    salt            TEXT                                NOT NULL,
    api_key         TEXT                                NULL,
    youtube_api_key TEXT                                NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS channel
(
    id                    TEXT    NOT NULL UNIQUE,
    handler               TEXT    NOT NULL UNIQUE,
    name                  TEXT    NOT NULL,
    avatar_url            TEXT    NOT NULL,
    description           TEXT    NOT NULL,
    channel_url           TEXT    NULL,
    channel_source        TEXT    NOT NULL,
    contain_keywords      TEXT    NULL,
    exclude_keywords      TEXT    NULL,
    minimum_duration      INTEGER NULL,
    initial_episodes      INTEGER NULL,
    maximum_episodes      INTEGER NULL,
    last_sync_video_id    TEXT,
    last_sync_timestamp   TIMESTAMP,
    subscribed_at         TIMESTAMP,
    UNIQUE (handler)
);

CREATE TABLE IF NOT EXISTS episode
(
    id                TEXT      NOT NULL UNIQUE,
    channel_id        TEXT      NOT NULL,
    position          INTEGER   NOT NULL,
    title             TEXT      NOT NULL,
    description       TEXT      NULL,
    published_at      TIMESTAMP NULL,
    default_cover_url TEXT      NULL,
    max_cover_url     TEXT      NULL,
    duration          TEXT,
    download_status   TEXT      NOT NULL,
    audio_file_path   TEXT,
    created_at        TIMESTAMP,
    FOREIGN KEY (channel_id) REFERENCES channel (id)
);

CREATE INDEX IF NOT EXISTS idx_program_title ON episode (title);
CREATE INDEX IF NOT EXISTS idx_program_description ON episode (description);
CREATE INDEX IF NOT EXISTS idx_program_channel_id ON episode (channel_id);