CREATE TABLE IF NOT EXISTS channel_new
(
    id                    TEXT    NOT NULL UNIQUE,
    handler               TEXT    NULL, 
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
    subscribed_at         TIMESTAMP
);

INSERT INTO channel_new (
    id, handler, name, avatar_url, description, channel_url, channel_source,
    contain_keywords, exclude_keywords, minimum_duration, initial_episodes,
    maximum_episodes, last_sync_video_id, last_sync_timestamp, subscribed_at
)
SELECT
    id, handler, name, avatar_url, description, channel_url, channel_source,
    contain_keywords, exclude_keywords, minimum_duration, initial_episodes,
    maximum_episodes, last_sync_video_id, last_sync_timestamp, subscribed_at
FROM channel;

DROP TABLE channel;

ALTER TABLE channel_new RENAME TO channel;