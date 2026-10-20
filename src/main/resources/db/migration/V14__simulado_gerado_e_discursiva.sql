-- Simulado montado pelo Claude: a prova (import_job) com as questoes chega em lotes; ao finalizar vira um bloco
-- com o perfil de pontuacao e o tempo do edital. group_name = prova do perfil que o simulado imita (ex.: P1).
CREATE TABLE generated_exam (
    id integer PRIMARY KEY AUTOINCREMENT,
    import_job_id bigint NOT NULL,
    scoring_profile_id bigint,
    group_name varchar(255),
    time_limit_minutes integer,
    -- Estilo e pegadinhas da banca que o Claude pesquisou antes de escrever as questoes, e de onde tirou.
    style_notes clob,
    style_sources clob,
    block_id bigint,
    created_at timestamp,
    finished_at timestamp
);
CREATE INDEX IF NOT EXISTS ix_generated_exam_job ON generated_exam(import_job_id);

-- Prova discursiva: o Claude cria o enunciado e os criterios, o usuario escreve no sistema com o tempo correndo
-- e o Claude grava a correcao (nota e comentarios).
CREATE TABLE essay (
    id integer PRIMARY KEY AUTOINCREMENT,
    title varchar(255) NOT NULL,
    prompt clob NOT NULL,
    banca varchar(255),
    criteria clob,
    time_limit_minutes integer,
    max_lines integer,
    max_score float,
    text clob,
    active_millis bigint,
    started_at timestamp,
    submitted_at timestamp,
    score float,
    feedback clob,
    corrected_at timestamp,
    created_at timestamp,
    updated_at timestamp
);
