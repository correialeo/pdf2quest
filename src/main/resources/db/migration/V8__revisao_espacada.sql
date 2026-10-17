-- Revisao espacada: etapa atual nos intervalos e data da proxima revisao de cada tarefa.
ALTER TABLE study_task ADD COLUMN current_step integer not null DEFAULT 0;
ALTER TABLE study_task ADD COLUMN next_review_date date;
CREATE INDEX ix_study_task_next_review ON study_task(next_review_date);

-- Backfill (so UPDATE): tarefas ja existentes ganham a primeira revisao no dia seguinte ao cadastro.
-- Datas ficam em epoch ms da meia-noite local, como o Hibernate grava LocalDate no SQLite.
UPDATE study_task
SET current_step = 0,
    next_review_date = CAST(strftime('%s', date(created_at / 1000, 'unixepoch', 'localtime', '+1 day'), 'utc') AS integer) * 1000
WHERE next_review_date IS NULL AND created_at IS NOT NULL;
