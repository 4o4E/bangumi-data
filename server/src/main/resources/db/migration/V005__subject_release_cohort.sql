ALTER TABLE bangumi_subject
    ADD COLUMN release_date DATE,
    ADD COLUMN platform INTEGER;

-- 旧数据代需要重新读取同一份 Archive，才能补齐同期比较需要的发行日期和载体。
UPDATE bangumi_catalog_generation
SET archive_imported = FALSE;
