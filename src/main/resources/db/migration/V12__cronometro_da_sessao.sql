-- Cronometro da sessao e modos "PROVA" (prova importada inteira) e "TREINO" (respostas dadas fora do sistema,
-- importadas no JSON). O CHECK do modo so muda recriando a tabela:
-- copia tudo para uma tabela nova com o CHECK novo e a coluna active_millis, e troca uma pela outra.
-- active_millis = tempo com o resolver aberto e sem pausa (o navegador manda o que contou); nulo nas sessoes
-- anteriores a esta versao.
CREATE TABLE study_session_new (
    id integer,
    correct_count integer not null,
    finished_at timestamp,
    mode varchar(255) check (mode in ('PRATICA','SIMULADO','REFAZER','PROVA','TREINO')),
    nota_dataprev float,
    percent float,
    question_ids_order clob,
    started_at timestamp,
    total_elapsed_millis bigint,
    wrong_count integer not null,
    block_id bigint,
    block_name varchar(255),
    time_limit_minutes integer,
    scoring_profile_id bigint,
    score_total float,
    score_report clob,
    active_millis bigint,
    primary key (id)
);
INSERT INTO study_session_new (id, correct_count, finished_at, mode, nota_dataprev, percent, question_ids_order,
    started_at, total_elapsed_millis, wrong_count, block_id, block_name, time_limit_minutes, scoring_profile_id,
    score_total, score_report)
SELECT id, correct_count, finished_at, mode, nota_dataprev, percent, question_ids_order, started_at,
    total_elapsed_millis, wrong_count, block_id, block_name, time_limit_minutes, scoring_profile_id, score_total,
    score_report FROM study_session;
DROP TABLE study_session;
ALTER TABLE study_session_new RENAME TO study_session;

-- Resposta dada depois do tempo limite do bloco.
ALTER TABLE attempt ADD COLUMN overtime boolean;

-- Consultas de respostas por questao/sessao (caderno de erros, mapa do resolver, painel).
CREATE INDEX IF NOT EXISTS ix_attempt_question ON attempt(question_id, answered_at);
CREATE INDEX IF NOT EXISTS ix_attempt_session ON attempt(session_id);
CREATE INDEX IF NOT EXISTS ix_question_import_job ON question(import_job_id);
