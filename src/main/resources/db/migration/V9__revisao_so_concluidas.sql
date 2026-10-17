-- Corrige o backfill da V8 (so UPDATE): a revisao comeca quando a tarefa e concluida.
-- Pendentes ficam sem revisao; concluidas revisam no dia seguinte a conclusao.
UPDATE study_task SET current_step = 0, next_review_date = NULL WHERE done = 0;

UPDATE study_task
SET current_step = 0,
    next_review_date = CAST(strftime('%s', date(done_at / 1000, 'unixepoch', 'localtime', '+1 day'), 'utc') AS integer) * 1000
WHERE done = 1 AND done_at IS NOT NULL AND current_step = 0;
