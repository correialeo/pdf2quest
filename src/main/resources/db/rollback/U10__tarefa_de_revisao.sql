-- Desfaz V10 (as tarefas de revisao ja criadas viram tarefas comuns).
DROP INDEX IF EXISTS ux_study_task_review_date;
ALTER TABLE study_task DROP COLUMN review_date;
DELETE FROM flyway_schema_history WHERE version = '10';
