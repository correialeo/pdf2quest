-- Corrige a etapa da revisao espacada (so UPDATE): concluir ja agenda o 1o intervalo, entao a etapa passa a ser
-- o indice do intervalo do proximo "lembrei". Antes o 1o intervalo era usado duas vezes.
UPDATE study_task SET current_step = current_step + 1
WHERE next_review_date IS NOT NULL AND review_date IS NULL AND current_step < 4;

-- Quem ja tinha revisado uma vez (agora etapa 2) foi reagendado com 1 dia em vez de 3: soma os 2 dias que faltaram.
UPDATE study_task SET next_review_date = next_review_date + 2 * 86400000
WHERE next_review_date IS NOT NULL AND review_date IS NULL AND current_step = 2;
