-- Desfaz V23. Rodar manualmente e depois: DELETE FROM flyway_schema_history WHERE version = '23';
-- Volta a uma tarefa de revisao por dia no total: apaga as extras do mesmo dia (fica a de menor id), que o sistema remonta.
DROP INDEX IF EXISTS ux_study_task_review_date_cronograma;
DELETE FROM study_task WHERE review_date IS NOT NULL
    AND id NOT IN (SELECT MIN(id) FROM study_task WHERE review_date IS NOT NULL GROUP BY review_date);
CREATE UNIQUE INDEX ux_study_task_review_date ON study_task(review_date) WHERE review_date IS NOT NULL;
