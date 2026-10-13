-- Desfaz V4. Rodar manualmente e depois: DELETE FROM flyway_schema_history WHERE version = '4';
ALTER TABLE import_job DROP COLUMN title;
