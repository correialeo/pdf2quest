-- Desfaz V29. Rodar manualmente e depois: DELETE FROM flyway_schema_history WHERE version = '29';
ALTER TABLE study_task DROP COLUMN review_items;
