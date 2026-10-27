-- Desfaz V20. Rodar manualmente e depois: DELETE FROM flyway_schema_history WHERE version = '20';
-- Quizzes Certo/Errado continuam com as letras C e E, so que exibidos como multipla escolha.
ALTER TABLE quiz DROP COLUMN format;
ALTER TABLE scoring_profile DROP COLUMN wrong_penalty;
