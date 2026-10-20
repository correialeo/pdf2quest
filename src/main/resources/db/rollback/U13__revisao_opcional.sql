-- Desfaz V13. Rodar manualmente e depois: DELETE FROM flyway_schema_history WHERE version = '13';
-- As tarefas marcadas para nao revisar voltam a entrar na revisao espacada.
ALTER TABLE study_task DROP COLUMN review_enabled;
