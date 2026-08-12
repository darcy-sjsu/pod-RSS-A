UPDATE episode
SET download_status = 'PENDING'
WHERE download_status = 'QUEUED'