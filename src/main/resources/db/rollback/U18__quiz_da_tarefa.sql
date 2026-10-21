-- Desfaz V18. Rodar manualmente e depois: DELETE FROM flyway_schema_history WHERE version = '18';
-- Os quizzes perdem a ligacao com a tarefa (as aulas continuam ligadas).
DROP INDEX IF EXISTS ix_lesson_task;
DROP INDEX IF EXISTS ix_quiz_task;
ALTER TABLE quiz DROP COLUMN task_id;
