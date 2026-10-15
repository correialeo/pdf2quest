-- Resumo do dia: pagina de Estudos ligada a uma data do cronograma (no maximo uma por dia).
ALTER TABLE study_page ADD COLUMN summary_date date;
CREATE UNIQUE INDEX ux_study_page_summary_date ON study_page(summary_date) WHERE summary_date IS NOT NULL;
