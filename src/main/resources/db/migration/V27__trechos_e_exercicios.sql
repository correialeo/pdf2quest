-- Fonte em texto literal: o agente baixa a fonte (curl/pdftotext) e grava o trecho de cada dispositivo citado; a aula
-- so cita artigo que tenha trecho gravado (o resumo do WebFetch nao serve de fonte).
CREATE TABLE source_excerpt (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    concurso_id BIGINT,
    lesson_id BIGINT,
    url VARCHAR(1000) NOT NULL,
    dispositivo VARCHAR(300) NOT NULL,
    texto clob NOT NULL,
    como_obtido VARCHAR(500) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    created_at TIMESTAMP
);
-- Itens de fixacao que nao sao multipla escolha: exercicio guiado (calculo) e tarefa de codigo, ligados ao bloco
-- da aula (block_index = ordem do bloco). O resultado entra no desempenho por topico como qualquer pergunta.
ALTER TABLE quiz_question ADD COLUMN kind VARCHAR(20);
ALTER TABLE quiz_question ADD COLUMN block_index INTEGER;
-- Dicas usadas na tentativa (exercicio guiado: dica, passo seguinte, resolucao completa).
ALTER TABLE quiz_attempt ADD COLUMN hints_used INTEGER NOT NULL DEFAULT 0;
