-- Desfaz V21. Rodar manualmente e depois: DELETE FROM flyway_schema_history WHERE version = '21';
-- Simulados Certo/Errado continuam com A = Certo e B = Errado, so que exibidos como multipla escolha.
ALTER TABLE generated_exam DROP COLUMN format;
