-- Importacao de questoes por JSON/CSV (sem PDF) e area de Estudos (paginas, cronograma, caixa de entrada).
-- So acrescenta colunas e tabelas: nada do que ja existe e alterado ou apagado.

-- Formato de origem da importacao: PDF, JSON ou CSV. Nulo = PDF (importacoes anteriores a esta versao).
ALTER TABLE import_job ADD COLUMN source_format varchar(20);

-- Identificador estavel vindo do arquivo importado (ex.: "fcc-2024-trt-q12"); liga o gabarito separado
-- e evita importar a mesma questao duas vezes. Unico quando preenchido.
ALTER TABLE question ADD COLUMN external_id varchar(255);
ALTER TABLE question ADD COLUMN question_type varchar(30);
ALTER TABLE question ADD COLUMN subtopics clob;
ALTER TABLE question ADD COLUMN tags clob;
-- Referencias e metadados livres do arquivo, guardados como JSON.
ALTER TABLE question ADD COLUMN extra_metadata clob;
CREATE UNIQUE INDEX ux_question_external_id ON question(external_id) WHERE external_id IS NOT NULL;

-- Paginas de estudo (resumos, anotacoes). parent_id aponta para a pagina-mae; nulo = pagina raiz.
-- content e HTML ja limpo pelo servidor; version cresce a cada gravacao (detecta abas desatualizadas).
CREATE TABLE study_page (
    id integer,
    parent_id bigint,
    title varchar(255) not null,
    content clob,
    subject varchar(255),
    position integer not null DEFAULT 0,
    version integer not null DEFAULT 0,
    created_at timestamp,
    updated_at timestamp,
    primary key (id)
);
CREATE INDEX ix_study_page_parent ON study_page(parent_id);

-- Tarefas do cronograma. page_id: resumo ligado a tarefa (opcional).
CREATE TABLE study_task (
    id integer,
    title varchar(255) not null,
    description clob,
    subject varchar(255),
    planned_date date,
    done boolean not null DEFAULT 0,
    done_at timestamp,
    estimated_minutes integer,
    spent_minutes integer,
    page_id bigint,
    created_at timestamp,
    primary key (id)
);
CREATE INDEX ix_study_task_date ON study_task(planned_date);

-- Caixa de entrada: anotacoes rapidas, organizadas depois.
CREATE TABLE inbox_note (
    id integer,
    content clob not null,
    subject varchar(255),
    created_at timestamp,
    primary key (id)
);
