-- Perfis de pontuacao.
CREATE TABLE scoring_profile (
    id integer,
    name varchar(255) not null,
    description clob,
    min_total float,
    created_at timestamp,
    primary key (id)
);

CREATE TABLE scoring_group (
    id integer,
    profile_id bigint not null,
    name varchar(255) not null,
    min_score float,
    position integer not null,
    primary key (id)
);

CREATE TABLE scoring_rule (
    id integer,
    group_id bigint not null,
    subject varchar(255) not null,
    match_subjects clob,
    weight float not null,
    question_count integer,
    position integer not null,
    primary key (id)
);

-- Blocos de estudo.
CREATE TABLE study_block (
    id integer,
    name varchar(255) not null,
    description clob,
    time_limit_minutes integer,
    scoring_profile_id bigint,
    created_at timestamp,
    primary key (id)
);

CREATE TABLE study_block_source (
    id integer,
    block_id bigint not null,
    position integer not null,
    import_job_id bigint,
    ranges varchar(255),
    subject varchar(255),
    topic varchar(255),
    organization varchar(255),
    year integer,
    max_questions integer,
    shuffle boolean DEFAULT 0,
    primary key (id)
);

CREATE TABLE study_block_exclusion (
    id integer,
    block_id bigint not null,
    question_id bigint,
    import_job_id bigint,
    question_number integer,
    topic varchar(255),
    primary key (id)
);

-- Sessoes iniciadas a partir de um bloco guardam o bloco (id e nome, para o export continuar legivel
-- mesmo se o bloco for editado ou apagado), o tempo limite e o resultado da pontuacao.
ALTER TABLE study_session ADD COLUMN block_id bigint;
ALTER TABLE study_session ADD COLUMN block_name varchar(255);
ALTER TABLE study_session ADD COLUMN time_limit_minutes integer;
ALTER TABLE study_session ADD COLUMN scoring_profile_id bigint;
ALTER TABLE study_session ADD COLUMN score_total float;
ALTER TABLE study_session ADD COLUMN score_report clob;
