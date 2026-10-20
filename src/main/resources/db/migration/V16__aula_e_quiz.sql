-- Aula + quiz do tutor (Claude pelo MCP). As perguntas do quiz ficam separadas das questoes de prova/simulado,
-- para nao misturar as metricas: cada alternativa tem a sua explicacao (mostrada ao clicar nela).
CREATE TABLE lesson (
    id integer PRIMARY KEY AUTOINCREMENT,
    title varchar(255) NOT NULL,
    subject varchar(255),
    edital_topic varchar(500),
    content clob,
    task_id bigint,
    created_at timestamp,
    finished_at timestamp,
    -- Refazer o quiz: respostas antes disto nao aparecem mais na tela (continuam no historico).
    restarted_at timestamp
);
CREATE TABLE quiz_question (
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
CREATE INDEX IF NOT EXISTS ix_quiz_question_lesson ON quiz_question(lesson_id);
CREATE TABLE quiz_attempt (
    id integer PRIMARY KEY AUTOINCREMENT,
    quiz_question_id bigint NOT NULL,
    selected_answer varchar(5),
    correct boolean,
    answered_at timestamp
);
CREATE INDEX IF NOT EXISTS ix_quiz_attempt_question ON quiz_attempt(quiz_question_id);
