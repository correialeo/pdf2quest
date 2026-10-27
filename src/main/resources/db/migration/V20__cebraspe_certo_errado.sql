-- Cebraspe: perfil em que cada erro desconta (1 = um erro anula um acerto) e quiz no formato Certo/Errado.
ALTER TABLE scoring_profile ADD COLUMN wrong_penalty float;
ALTER TABLE quiz ADD COLUMN format varchar(20);
