-- Add custom title and custom cover storage path for feeds
ALTER TABLE channel ADD COLUMN custom_title TEXT NULL;
ALTER TABLE channel ADD COLUMN last_updated_at TIMESTAMP NULL;
ALTER TABLE channel ADD COLUMN custom_cover_ext TEXT NULL;

ALTER TABLE playlist ADD COLUMN custom_title TEXT NULL;
ALTER TABLE playlist ADD COLUMN last_updated_at TIMESTAMP NULL;
ALTER TABLE playlist ADD COLUMN custom_cover_ext TEXT NULL;