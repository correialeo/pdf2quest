-- Baseline: schema criado antes do Flyway (via hibernate ddl-auto=update).
-- Bancos que ja tinham esse schema nao executam este script (baseline-on-migrate
-- marca a versao 1 sem rodar nada); ele so serve para criar um banco novo.
CREATE TABLE attempt (
    id integer,
    answered_at timestamp,
    correct boolean,
    elapsed_milliseconds bigint,
    question_id bigint,
    selected_answer varchar(255),
    session_id bigint,
    primary key (id)
);

CREATE TABLE import_job (
    id integer,
    banca varchar(255),
    detected_organization varchar(255),
    detected_year integer,
    error_log clob,
    failed_questions integer not null,
    file_name varchar(255),
    finished_at timestamp,
    imported_questions integer not null,
    started_at timestamp,
    pdf_path varchar(255),
    primary key (id)
);

CREATE TABLE question (
    id integer,
    alternativea clob,
    alternativeb clob,
    alternativec clob,
    alternatived clob,
    alternativee clob,
    category varchar(255) check (category in ('GERAL','ESPECIFICO')),
    correct_answer varchar(255),
    created_at timestamp,
    difficulty varchar(255),
    exam varchar(255),
    explanation clob,
    import_job_id bigint,
    organization varchar(255),
    question_number integer,
    source varchar(255),
    statement clob not null,
    subject varchar(255),
    topic varchar(255),
    year integer,
    page integer,
    passage_id bigint,
    excluded boolean,
    note clob,
    primary key (id)
);

CREATE TABLE study_session (
    id integer,
    correct_count integer not null,
    finished_at timestamp,
    mode varchar(255) check (mode in ('PRATICA','SIMULADO','REFAZER')),
    nota_dataprev float,
    percent float,
    question_ids_order clob,
    started_at timestamp,
    total_elapsed_millis bigint,
    wrong_count integer not null,
    primary key (id)
);

CREATE TABLE passage (
    id integer,
    content clob not null,
    import_job_id bigint,
    primary key (id)
);

CREATE TABLE question_image (
    id integer,
    data blob not null,
    import_job_id bigint,
    page integer,
    primary key (id)
);
