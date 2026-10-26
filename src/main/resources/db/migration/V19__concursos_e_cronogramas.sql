-- Concursos (workspace) e cronogramas. Tudo opcional: registro sem concurso continua aparecendo em "Todos".
CREATE TABLE concurso (
    id integer PRIMARY KEY AUTOINCREMENT,
    nome varchar(255) NOT NULL,
    orgao varchar(255),
    banca varchar(100),
    cargo varchar(255),
    data_prova date,
    data_inscricao_fim date,
    link_edital varchar(1000),
    status varchar(30) NOT NULL DEFAULT 'ESTUDANDO',
    -- Perfil de pontuacao/edital do concurso (um perfil generico pode servir a varios).
    scoring_profile_id bigint,
    cor varchar(20),
    notas clob,
    created_at timestamp,
    arquivado_em timestamp
);
CREATE TABLE cronograma (
    id integer PRIMARY KEY AUTOINCREMENT,
    concurso_id bigint,
    nome varchar(255) NOT NULL,
    inicio date,
    fim date,
    ativo boolean NOT NULL DEFAULT 1,
    created_at timestamp
);
CREATE INDEX IF NOT EXISTS ix_cronograma_concurso ON cronograma(concurso_id);
-- Preferencias simples (chave/valor): guarda o concurso ativo, compartilhado entre a tela e o MCP.
CREATE TABLE app_setting (
    chave varchar(100) PRIMARY KEY,
    valor varchar(1000)
);

ALTER TABLE study_task ADD COLUMN cronograma_id bigint;
CREATE INDEX IF NOT EXISTS ix_study_task_cronograma ON study_task(cronograma_id);
ALTER TABLE study_block ADD COLUMN concurso_id bigint;
ALTER TABLE generated_exam ADD COLUMN concurso_id bigint;
ALTER TABLE study_session ADD COLUMN concurso_id bigint;
CREATE INDEX IF NOT EXISTS ix_study_session_concurso ON study_session(concurso_id);
ALTER TABLE lesson ADD COLUMN concurso_id bigint;
ALTER TABLE quiz ADD COLUMN concurso_id bigint;
ALTER TABLE essay ADD COLUMN concurso_id bigint;
ALTER TABLE import_job ADD COLUMN concurso_id bigint;
ALTER TABLE study_page ADD COLUMN concurso_id bigint;

-- Backfill: com um unico perfil de pontuacao, ele vira o concurso e tudo o que ja existe passa a ser dele.
INSERT INTO concurso (nome, scoring_profile_id, status, created_at)
SELECT name, id, 'ESTUDANDO', CAST(strftime('%s', 'now') AS INTEGER) * 1000 FROM scoring_profile
WHERE (SELECT COUNT(*) FROM scoring_profile) = 1;

INSERT INTO cronograma (concurso_id, nome, ativo, created_at)
SELECT id, 'Geral', 1, CAST(strftime('%s', 'now') AS INTEGER) * 1000 FROM concurso;

UPDATE study_task SET cronograma_id = (SELECT MIN(id) FROM cronograma) WHERE EXISTS (SELECT 1 FROM cronograma);
UPDATE study_block SET concurso_id = (SELECT MIN(id) FROM concurso) WHERE EXISTS (SELECT 1 FROM concurso);
UPDATE generated_exam SET concurso_id = (SELECT MIN(id) FROM concurso) WHERE EXISTS (SELECT 1 FROM concurso);
UPDATE study_session SET concurso_id = (SELECT MIN(id) FROM concurso) WHERE EXISTS (SELECT 1 FROM concurso);
UPDATE lesson SET concurso_id = (SELECT MIN(id) FROM concurso) WHERE EXISTS (SELECT 1 FROM concurso);
UPDATE quiz SET concurso_id = (SELECT MIN(id) FROM concurso) WHERE EXISTS (SELECT 1 FROM concurso);
UPDATE essay SET concurso_id = (SELECT MIN(id) FROM concurso) WHERE EXISTS (SELECT 1 FROM concurso);
INSERT INTO app_setting (chave, valor) SELECT 'concurso_ativo', CAST(MIN(id) AS TEXT) FROM concurso
WHERE EXISTS (SELECT 1 FROM concurso);
