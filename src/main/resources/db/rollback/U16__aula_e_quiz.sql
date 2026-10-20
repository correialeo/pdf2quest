-- Desfaz V16. Rodar manualmente e depois: DELETE FROM flyway_schema_history WHERE version = '16';
-- Faca backup antes: aulas, quizzes e respostas sao perdidos.
DROP INDEX IF EXISTS ix_quiz_attempt_question;
DROP TABLE IF EXISTS quiz_attempt;
DROP INDEX IF EXISTS ix_quiz_question_lesson;
DROP TABLE IF EXISTS quiz_question;
DROP TABLE IF EXISTS lesson;
