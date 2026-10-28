-- Desfaz V22. Rodar manualmente e depois: DELETE FROM flyway_schema_history WHERE version = '22';
-- Volta a um resumo por dia no total: falha se houver dois concursos com resumo no mesmo dia (apague ou mude a data
-- de um deles antes).
DROP INDEX IF EXISTS ux_study_page_summary_date_concurso;
CREATE UNIQUE INDEX ux_study_page_summary_date ON study_page(summary_date) WHERE summary_date IS NOT NULL;
