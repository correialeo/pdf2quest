-- Revisao em camadas das questoes escritas pelo Claude (so acrescenta tabelas; nada existente muda).
-- question_review: uma linha por questao com o resultado das revisoes de fidelidade a banca (B) e de correcao
-- material (C) e o topico do edital (D). content_hash = conteudo da questao quando foi revisada: se a questao
-- muda depois, a revisao deixa de valer.
CREATE TABLE question_review (
    id integer PRIMARY KEY AUTOINCREMENT,
    question_id bigint NOT NULL UNIQUE,
    content_hash varchar(64),
    fidelity_status varchar(30),
    fidelity_score float,
    fidelity_criteria clob,
    fidelity_references clob,
    fidelity_issues clob,
    fidelity_hash varchar(64),
    fidelity_at timestamp,
    material_status varchar(30),
    content_type varchar(40),
    independent_answer varchar(10),
    defensible_answers varchar(20),
    calculation clob,
    sources clob,
    temporal_reference varchar(500),
    historical_rule boolean,
    material_notes clob,
    material_issues clob,
    material_hash varchar(64),
    material_at timestamp,
    edital_topic varchar(500),
    updated_at timestamp
);
-- Versoes anteriores de questoes corrigidas (o simulado original continua comparavel com o corrigido).
CREATE TABLE question_revision (
    id integer PRIMARY KEY AUTOINCREMENT,
    question_id bigint NOT NULL,
    statement clob,
    alternatives clob,
    correct_answer varchar(10),
    explanation clob,
    review_snapshot clob,
    reason varchar(500),
    created_at timestamp
);
CREATE INDEX IF NOT EXISTS ix_question_revision_question ON question_revision(question_id);
-- Conteudo programatico do edital por disciplina do perfil, para conferir a cobertura dos simulados.
CREATE TABLE edital_topic (
    id integer PRIMARY KEY AUTOINCREMENT,
    scoring_profile_id bigint NOT NULL,
    subject varchar(255) NOT NULL,
    topic varchar(500) NOT NULL,
    position integer
);
CREATE INDEX IF NOT EXISTS ix_edital_topic_profile ON edital_topic(scoring_profile_id, subject);
