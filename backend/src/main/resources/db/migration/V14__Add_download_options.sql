ALTER TABLE channel ADD COLUMN download_type VARCHAR(255) DEFAULT 'AUDIO' NOT NULL;
ALTER TABLE channel ADD COLUMN video_quality VARCHAR(255);

ALTER TABLE playlist ADD COLUMN download_type VARCHAR(255) DEFAULT 'AUDIO' NOT NULL;
ALTER TABLE playlist ADD COLUMN video_quality VARCHAR(255);

ALTER TABLE episode RENAME COLUMN audio_file_path TO media_file_path;
ALTER TABLE episode ADD COLUMN media_type VARCHAR(255);
