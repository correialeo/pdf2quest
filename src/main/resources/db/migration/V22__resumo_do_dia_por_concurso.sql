-- Resumo do dia por concurso (workspace): no maximo um por dia em cada concurso, e nao mais um por dia no total.
-- Sem concurso (resumo geral de "Todos") conta como concurso 0 no indice.
DROP INDEX IF EXISTS ux_study_page_summary_date;
CREATE UNIQUE INDEX ux_study_page_summary_date_concurso
    ON study_page(summary_date, COALESCE(concurso_id, 0)) WHERE summary_date IS NOT NULL;
