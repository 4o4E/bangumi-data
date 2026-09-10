ALTER TABLE bangumi_subject
    ADD COLUMN favorite_wish BIGINT,
    ADD COLUMN favorite_done BIGINT,
    ADD COLUMN favorite_doing BIGINT,
    ADD COLUMN favorite_on_hold BIGINT,
    ADD COLUMN favorite_dropped BIGINT;

ALTER TABLE bangumi_subject_character
    ADD COLUMN selection_version INTEGER NOT NULL DEFAULT 1;

-- 旧数据代需重新读取同一份 Archive 才能补齐作品热度；角色详情补充进度会直接复用。
UPDATE bangumi_catalog_generation generation
SET archive_imported = FALSE
WHERE EXISTS (
    SELECT 1 FROM bangumi_subject subject
    WHERE subject.generation_id = generation.id AND subject.favorite_wish IS NULL
);

CREATE INDEX idx_bangumi_subject_favorites
    ON bangumi_subject(
        generation_id,
        type,
        ((favorite_wish + favorite_done + favorite_doing + favorite_on_hold + favorite_dropped))
    );
