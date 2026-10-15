-- 性别说明解析支持固定性转和男女双形态后，已完成的详情也需要重新读取一次。
-- 只安排熟悉度候选，保留旧详情和 eligible，使当前目录在分批补充期间继续可查询。
UPDATE bangumi_character AS character
SET enriched = FALSE
WHERE character.enriched = TRUE
  AND EXISTS (
      SELECT 1
      FROM bangumi_subject_character AS relation
      WHERE relation.generation_id = character.generation_id
        AND relation.character_id = character.id
        AND relation.familiarity IN ('CORE', 'FAMILIAR')
  );
