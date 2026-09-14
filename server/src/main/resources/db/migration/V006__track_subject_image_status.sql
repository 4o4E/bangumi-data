ALTER TABLE bangumi_subject
    ADD COLUMN image_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN image_retry_at BIGINT,
    ADD CONSTRAINT chk_bangumi_subject_image_status CHECK (
        image_status IN ('PENDING', 'AVAILABLE', 'MISSING_UPSTREAM', 'NOT_FOUND', 'FETCH_FAILED')
    );

-- 历史空封面无法区分上游无图、404 与旧 NSFW 隐藏策略，统一重新请求一次建立准确状态。
UPDATE bangumi_subject
SET enriched = FALSE,
    image_status = 'PENDING',
    image_retry_at = NULL
WHERE image_url IS NULL;

UPDATE bangumi_subject
SET image_status = 'AVAILABLE',
    image_retry_at = NULL
WHERE image_url IS NOT NULL;
