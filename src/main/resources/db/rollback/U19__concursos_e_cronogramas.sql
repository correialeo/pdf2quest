-- Desfaz V19. Rodar manualmente e depois: DELETE FROM flyway_schema_history WHERE version = '19';
-- Tarefas, aulas, simulados etc. perdem a ligacao com concurso/cronograma (nada e apagado).
ALTER TABLE study_page DROP COLUMN concurso_id;
ALTER TABLE import_job DROP COLUMN concurso_id;
ALTER TABLE essay DROP COLUMN concurso_id;
ALTER TABLE quiz DROP COLUMN concurso_id;
ALTER TABLE lesson DROP COLUMN concurso_id;
DROP INDEX IF EXISTS ix_study_session_concurso;
ALTER TABLE study_session DROP COLUMN concurso_id;
ALTER TABLE generated_exam DROP COLUMN concurso_id;
ALTER TABLE study_block DROP COLUMN concurso_id;
DROP INDEX IF EXISTS ix_study_task_cronograma;
ALTER TABLE study_task DROP COLUMN cronograma_id;
DROP TABLE IF EXISTS app_setting;
DROP INDEX IF EXISTS ix_cronograma_concurso;
DROP TABLE IF EXISTS cronograma;
DROP TABLE IF EXISTS concurso;
