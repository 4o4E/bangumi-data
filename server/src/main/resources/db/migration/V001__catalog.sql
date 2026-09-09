CREATE TABLE IF NOT EXISTS bangumi_catalog_generation (
    id VARCHAR(80) PRIMARY KEY,
    source_version VARCHAR(255) NOT NULL,
    source_digest VARCHAR(128) NOT NULL,
    source_created_at BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at BIGINT NOT NULL,
    activated_at BIGINT,
    error TEXT
);

CREATE TABLE IF NOT EXISTS bangumi_catalog_state (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    active_generation_id VARCHAR(80) REFERENCES bangumi_catalog_generation(id),
    sync_running BOOLEAN NOT NULL DEFAULT FALSE,
    sync_phase VARCHAR(80),
    last_error TEXT
);

INSERT INTO bangumi_catalog_state(singleton) VALUES (TRUE) ON CONFLICT(singleton) DO NOTHING;

CREATE TABLE IF NOT EXISTS bangumi_subject (
    generation_id VARCHAR(80) NOT NULL REFERENCES bangumi_catalog_generation(id) ON DELETE CASCADE,
    id BIGINT NOT NULL,
    type INTEGER NOT NULL,
    name TEXT NOT NULL,
    name_cn TEXT NOT NULL,
    summary TEXT,
    image_url TEXT,
    nsfw BOOLEAN NOT NULL,
    source_updated_at BIGINT NOT NULL,
    PRIMARY KEY (generation_id, id)
);

CREATE TABLE IF NOT EXISTS bangumi_character (
    generation_id VARCHAR(80) NOT NULL REFERENCES bangumi_catalog_generation(id) ON DELETE CASCADE,
    id BIGINT NOT NULL,
    entity_type INTEGER NOT NULL,
    name TEXT NOT NULL,
    name_cn TEXT,
    aliases TEXT[] NOT NULL DEFAULT '{}',
    gender VARCHAR(16),
    summary TEXT,
    image_url TEXT,
    nsfw BOOLEAN NOT NULL DEFAULT FALSE,
    comments BIGINT NOT NULL,
    collects BIGINT NOT NULL,
    eligible BOOLEAN NOT NULL DEFAULT FALSE,
    enriched BOOLEAN NOT NULL DEFAULT FALSE,
    source_updated_at BIGINT NOT NULL,
    PRIMARY KEY (generation_id, id)
);

CREATE TABLE IF NOT EXISTS bangumi_subject_character (
    generation_id VARCHAR(80) NOT NULL,
    subject_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL,
    relation_type INTEGER NOT NULL,
    relation_order INTEGER NOT NULL,
    popularity_rank INTEGER,
    familiarity VARCHAR(20) NOT NULL DEFAULT 'LONG_TAIL',
    selection_reason VARCHAR(20),
    PRIMARY KEY (generation_id, subject_id, character_id),
    FOREIGN KEY (generation_id, subject_id) REFERENCES bangumi_subject(generation_id, id) ON DELETE CASCADE,
    FOREIGN KEY (generation_id, character_id) REFERENCES bangumi_character(generation_id, id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_bangumi_character_pool
    ON bangumi_character(generation_id, gender, eligible, id);
CREATE INDEX IF NOT EXISTS idx_bangumi_subject_character_character
    ON bangumi_subject_character(generation_id, character_id, familiarity);
