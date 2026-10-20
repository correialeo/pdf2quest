-- Desfaz V17. Rodar manualmente e depois: DELETE FROM flyway_schema_history WHERE version = '17';
-- Faca backup antes: quizzes sem aula e a separacao entre quizzes da mesma aula sao perdidos.
DELETE FROM quiz_question WHERE lesson_id IS NULL;
CREATE TABLE quiz_question_old (
    id integer PRIMARY KEY AUTOINCREMENT,
    lesson_id bigint NOT NULL,
    position integer,
    subject varchar(255),
    topic varchar(500),
    statement clob NOT NULL,
    alternatives clob NOT NULL,
    correct_answer varchar(5) NOT NULL,
    created_at timestamp
);
INSERT INTO quiz_question_old SELECT id, lesson_id, position, subject, topic, statement, alternatives,
    correct_answer, created_at FROM quiz_question;
DROP INDEX IF EXISTS ix_quiz_question_quiz;
DROP TABLE quiz_question;
ALTER TABLE quiz_question_old RENAME TO quiz_question;
CREATE INDEX IF NOT EXISTS ix_quiz_question_lesson ON quiz_question(lesson_id);
DROP INDEX IF EXISTS ix_quiz_lesson;
DROP TABLE quiz;
