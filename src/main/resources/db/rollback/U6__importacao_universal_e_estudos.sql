-- Desfaz V6. ATENCAO: apaga paginas, tarefas e anotacoes da area de Estudos.
DROP TABLE IF EXISTS inbox_note;
DROP TABLE IF EXISTS study_task;
DROP TABLE IF EXISTS study_page;
DROP INDEX IF EXISTS ux_question_external_id;
ALTER TABLE question DROP COLUMN extra_metadata;
ALTER TABLE question DROP COLUMN tags;
ALTER TABLE question DROP COLUMN subtopics;
ALTER TABLE question DROP COLUMN question_type;
ALTER TABLE question DROP COLUMN external_id;
ALTER TABLE import_job DROP COLUMN source_format;
DELETE FROM flyway_schema_history WHERE version = '6';
