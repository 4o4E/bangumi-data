ALTER TABLE bangumi_subject
    ADD COLUMN enriched BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE bangumi_catalog_generation
    ADD COLUMN archive_imported BOOLEAN NOT NULL DEFAULT FALSE;

-- 已成功取得封面的旧记录视为已补充；其余记录会在新进程中安全重试。
UPDATE bangumi_subject
SET enriched = TRUE
WHERE image_url IS NOT NULL;

-- V001 的 Archive 导入在单个事务内完成，三类记录都存在即可安全视为完整基线。
UPDATE bangumi_catalog_generation generation
SET archive_imported = TRUE
WHERE EXISTS (SELECT 1 FROM bangumi_subject WHERE generation_id = generation.id)
  AND EXISTS (SELECT 1 FROM bangumi_character WHERE generation_id = generation.id)
  AND EXISTS (SELECT 1 FROM bangumi_subject_character WHERE generation_id = generation.id);

CREATE INDEX idx_bangumi_subject_pending_enrichment
    ON bangumi_subject(generation_id, id)
    WHERE enriched = FALSE;
