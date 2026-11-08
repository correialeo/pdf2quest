-- Revisao com foco: a tarefa diaria guarda os assuntos escolhidos para o dia (ids separados por virgula), para que
-- concluir a tarefa avance so esses e o foco nao mude no meio do dia (os quizzes do dia mexem na medida de fraqueza).
ALTER TABLE study_task ADD COLUMN review_items varchar(2000);
