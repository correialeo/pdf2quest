-- Revisao espacada opcional por tarefa: falso = concluir nao agenda revisao (ex.: "Descanso", "Prova").
ALTER TABLE study_task ADD COLUMN review_enabled boolean NOT NULL DEFAULT 1;
