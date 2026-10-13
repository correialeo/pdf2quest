-- "E chute?": marcado pelo usuario ao responder. Nulo = nao informado (respostas anteriores a esta versao);
-- falso/verdadeiro = informado.
ALTER TABLE attempt ADD COLUMN guessed boolean;
