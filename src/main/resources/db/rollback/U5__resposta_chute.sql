-- Desfaz V5. Rodar manualmente e depois: DELETE FROM flyway_schema_history WHERE version = '5';
-- Faca backup do banco antes: as marcacoes de chute sao perdidas.
ALTER TABLE attempt DROP COLUMN guessed;
