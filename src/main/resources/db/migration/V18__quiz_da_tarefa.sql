-- Quiz ligado a uma tarefa do cronograma (a aula ja tinha task_id desde V16).
ALTER TABLE quiz ADD COLUMN task_id bigint;
CREATE INDEX IF NOT EXISTS ix_quiz_task ON quiz(task_id);
CREATE INDEX IF NOT EXISTS ix_lesson_task ON lesson(task_id);
