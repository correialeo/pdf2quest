-- Desfaz V12. Rodar manualmente e depois: DELETE FROM flyway_schema_history WHERE version = '12';
-- Faca backup do banco antes: o tempo contado das sessoes e as marcacoes de fora do tempo sao perdidos, e as
-- sessoes de prova completa (modo PROVA) voltam a ser SIMULADO e as de treino importado (TREINO), PRATICA.
DROP INDEX IF EXISTS ix_question_import_job;
DROP INDEX IF EXISTS ix_attempt_session;
DROP INDEX IF EXISTS ix_attempt_question;
ALTER TABLE attempt DROP COLUMN overtime;
UPDATE study_session SET mode = 'SIMULADO' WHERE mode = 'PROVA';
UPDATE study_session SET mode = 'PRATICA' WHERE mode = 'TREINO';
CREATE TABLE study_session_old (
    id integer,
    correct_count integer not null,
    finished_at timestamp,
    mode varchar(255) check (mode in ('PRATICA','SIMULADO','REFAZER')),
    nota_dataprev float,
    percent float,
    question_ids_order clob,
    started_at timestamp,
    total_elapsed_millis bigint,
    wrong_count integer not null, block_id bigint, block_name varchar(255), time_limit_minutes integer,
    scoring_profile_id bigint, score_total float, score_report clob,
    primary key (id)
);
INSERT INTO study_session_old SELECT id, correct_count, finished_at, mode, nota_dataprev, percent,
    question_ids_order, started_at, total_elapsed_millis, wrong_count, block_id, block_name, time_limit_minutes,
    scoring_profile_id, score_total, score_report FROM study_session;
DROP TABLE study_session;
ALTER TABLE study_session_old RENAME TO study_session;
