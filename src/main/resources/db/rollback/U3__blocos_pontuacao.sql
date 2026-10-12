-- Desfaz V3. Rodar manualmente e depois: DELETE FROM flyway_schema_history WHERE version = '3';
-- Faca backup do banco antes: blocos, perfis e o resultado das sessoes de bloco sao perdidos.
ALTER TABLE study_session DROP COLUMN score_report;
ALTER TABLE study_session DROP COLUMN score_total;
ALTER TABLE study_session DROP COLUMN scoring_profile_id;
ALTER TABLE study_session DROP COLUMN time_limit_minutes;
ALTER TABLE study_session DROP COLUMN block_name;
ALTER TABLE study_session DROP COLUMN block_id;

DROP TABLE study_block_exclusion;
DROP TABLE study_block_source;
DROP TABLE study_block;
DROP TABLE scoring_rule;
DROP TABLE scoring_group;
DROP TABLE scoring_profile;
