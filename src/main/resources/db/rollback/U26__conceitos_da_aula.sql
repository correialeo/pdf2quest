-- Desfaz V26. Rodar manualmente e depois: DELETE FROM flyway_schema_history WHERE version = '26';
ALTER TABLE quiz_question DROP COLUMN concepts;
ALTER TABLE quiz_question DROP COLUMN beyond_lesson;
