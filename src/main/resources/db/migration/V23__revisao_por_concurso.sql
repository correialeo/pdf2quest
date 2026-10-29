-- Tarefa de revisao por concurso (workspace): no maximo uma por dia em cada cronograma, e nao mais uma por dia no total.
-- A revisao de um concurso fica no cronograma padrao dele; sem cronograma (assuntos avulsos) conta como 0 no indice.
DROP INDEX IF EXISTS ux_study_task_review_date;
CREATE UNIQUE INDEX ux_study_task_review_date_cronograma
    ON study_task(review_date, COALESCE(cronograma_id, 0)) WHERE review_date IS NOT NULL;
-- A revisao aberta montada antes disto misturava os assuntos de todos os concursos: o sistema a remonta por concurso.
DELETE FROM study_task WHERE review_date IS NOT NULL AND done = 0 AND cronograma_id IS NULL;
