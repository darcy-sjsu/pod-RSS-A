DROP INDEX IF EXISTS idx_program_title;
DROP INDEX IF EXISTS idx_program_description;
DROP INDEX IF EXISTS idx_program_channel_id;

UPDATE channel SET initial_episodes = 3 WHERE initial_episodes IS NULL;