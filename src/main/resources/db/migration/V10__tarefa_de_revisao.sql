-- Revisao espacada como tarefa: no maximo uma tarefa de revisao por dia (review_date = dia em que foi montada).
ALTER TABLE study_task ADD COLUMN review_date date;
CREATE UNIQUE INDEX ux_study_task_review_date ON study_task(review_date) WHERE review_date IS NOT NULL;
