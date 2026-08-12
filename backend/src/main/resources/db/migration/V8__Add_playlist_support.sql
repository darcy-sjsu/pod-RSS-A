CREATE TABLE IF NOT EXISTS playlist
(
    id                  TEXT PRIMARY KEY NOT NULL,
    title               TEXT             NOT NULL,
    owner_id            TEXT             NULL,
    cover_url           TEXT             NOT NULL,
    description         TEXT             NOT NULL,
    source              TEXT             NOT NULL,
    contain_keywords    TEXT             NULL,
    exclude_keywords    TEXT             NULL,
    minimum_duration    INTEGER          NULL,
    initial_episodes    INTEGER          NULL,
    maximum_episodes    INTEGER          NULL,
    last_sync_video_id  TEXT,
    last_sync_timestamp TIMESTAMP,
    subscribed_at       TIMESTAMP
);

CREATE TABLE IF NOT EXISTS playlist_episode
(
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    playlist_id  TEXT      NOT NULL,
    episode_id   TEXT      NOT NULL,
    position     INTEGER   NULL,
    published_at TIMESTAMP NULL
);