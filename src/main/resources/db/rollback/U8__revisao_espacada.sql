-- Desfaz V8 (as tarefas continuam, so perdem o agendamento das revisoes).
DROP INDEX IF EXISTS ix_study_task_next_review;
ALTER TABLE study_task DROP COLUMN next_review_date;
ALTER TABLE study_task DROP COLUMN current_step;
DELETE FROM flyway_schema_history WHERE version = '8';
