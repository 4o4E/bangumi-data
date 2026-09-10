-- 同一份 Archive 需要重新导入，才能补回此前因缺少中文名或条目 NSFW 标记而跳过的作品。
UPDATE bangumi_catalog_generation
SET archive_imported = FALSE;

-- 旧版本可能已经把“没有中文名”的角色标记为补充完成，需要重新读取原名并判断资格。
UPDATE bangumi_character
SET enriched = FALSE
WHERE COALESCE(BTRIM(name_cn), '') = '';

-- 防御历史及后续写入：在图片级安全审核能力落地前，NSFW 条目不向消费者暴露封面。
UPDATE bangumi_subject
SET image_url = NULL
WHERE nsfw = TRUE;
