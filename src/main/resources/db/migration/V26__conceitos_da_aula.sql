-- Rastreabilidade aula <-> quiz: cada pergunta diz que conceito(s) da aula cobra ({#id} nos slides e caixas).
-- ampliacao = pergunta que vai alem da aula de proposito (listada como "alem da aula" no resultado).
ALTER TABLE quiz_question ADD COLUMN concepts varchar(500);
ALTER TABLE quiz_question ADD COLUMN beyond_lesson boolean NOT NULL DEFAULT 0;
