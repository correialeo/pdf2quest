-- Quiz vira entidade propria: uma aula pode ter varios quizzes e um quiz pode existir sem aula. Os quizzes ja
-- feitos viram o "Quiz 1" da aula, com as mesmas perguntas e respostas.
CREATE TABLE quiz (
    id integer PRIMARY KEY AUTOINCREMENT,
    lesson_id bigint,
    title varchar(255) NOT NULL,
    subject varchar(255),
    topic varchar(500),
    level varchar(30),
    doubts clob,
    previous_errors clob,
    created_at timestamp,
    finished_at timestamp,
    restarted_at timestamp
);
CREATE INDEX IF NOT EXISTS ix_quiz_lesson ON quiz(lesson_id);
INSERT INTO quiz (lesson_id, title, subject, topic, created_at, finished_at, restarted_at)
SELECT id, 'Quiz 1', subject, edital_topic, created_at, finished_at, restarted_at FROM lesson
WHERE id IN (SELECT lesson_id FROM quiz_question);

-- quiz_question: lesson_id deixa de ser obrigatorio (quiz sem aula). O SQLite so muda isso recriando a tabela.
CREATE TABLE quiz_question_new (
    id integer PRIMARY KEY AUTOINCREMENT,
    quiz_id bigint NOT NULL,
    lesson_id bigint,
    position integer,
    subject varchar(255),
    topic varchar(500),
    statement clob NOT NULL,
    alternatives clob NOT NULL,
    correct_answer varchar(5) NOT NULL,
    created_at timestamp
);
INSERT INTO quiz_question_new (id, quiz_id, lesson_id, position, subject, topic, statement, alternatives,
    correct_answer, created_at)
SELECT qq.id, (SELECT q.id FROM quiz q WHERE q.lesson_id = qq.lesson_id), qq.lesson_id, qq.position, qq.subject,
    qq.topic, qq.statement, qq.alternatives, qq.correct_answer, qq.created_at FROM quiz_question qq;
DROP INDEX IF EXISTS ix_quiz_question_lesson;
DROP TABLE quiz_question;
ALTER TABLE quiz_question_new RENAME TO quiz_question;
CREATE INDEX IF NOT EXISTS ix_quiz_question_quiz ON quiz_question(quiz_id);
