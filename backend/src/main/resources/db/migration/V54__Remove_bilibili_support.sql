-- Bilibili platform support has been removed from the application.
-- Existing Bilibili rows are unserviceable: their feed ids (bili-mid-*, bili-season-*,
-- bili-series-*) and episode ids (bvid) would be handed to the YouTube Data API by the
-- scheduler, failing on every sync. Purge them so no orphaned feeds remain.

-- Episodes owned by a Bilibili channel.
-- Keep shared episodes that are still referenced by a surviving playlist. Detach
-- them from the channel before deleting Bilibili-owned episode rows so the
-- surviving playlist mapping does not become orphaned.
UPDATE episode
SET channel_id = NULL
WHERE channel_id IN (SELECT id FROM channel WHERE UPPER(source) = 'BILIBILI')
  AND EXISTS (SELECT 1
              FROM playlist_episode pe
              JOIN playlist p ON p.id = pe.playlist_id
              WHERE pe.episode_id = episode.id
                AND UPPER(p.source) <> 'BILIBILI');

DELETE FROM episode
WHERE channel_id IN (SELECT id FROM channel WHERE UPPER(source) = 'BILIBILI');

-- Episodes that exist only inside a Bilibili playlist. Episodes still referenced by a
-- surviving playlist or channel are intentionally left untouched.
DELETE FROM episode
WHERE id IN (SELECT episode_id
             FROM playlist_episode
             WHERE playlist_id IN (SELECT id FROM playlist WHERE UPPER(source) = 'BILIBILI'))
  AND id NOT IN (SELECT episode_id
                 FROM playlist_episode
                 WHERE playlist_id NOT IN (SELECT id FROM playlist WHERE UPPER(source) = 'BILIBILI'))
  AND (channel_id IS NULL
    OR channel_id = ''
    OR channel_id NOT IN (SELECT id FROM channel));

DELETE FROM playlist_episode
WHERE playlist_id IN (SELECT id FROM playlist WHERE UPPER(source) = 'BILIBILI');

DELETE FROM channel WHERE UPPER(source) = 'BILIBILI';

DELETE FROM playlist WHERE UPPER(source) = 'BILIBILI';

DELETE FROM cookie_config WHERE UPPER(platform) = 'BILIBILI';
