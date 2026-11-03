-- Desfaz V27. Rodar manualmente e depois: DELETE FROM flyway_schema_history WHERE version = '27';
DELETE FROM quiz_attempt WHERE quiz_question_id IN (SELECT id FROM quiz_question WHERE kind IS NOT NULL);
DELETE FROM quiz_question WHERE kind IS NOT NULL;
ALTER TABLE quiz_attempt DROP COLUMN hints_used;
ALTER TABLE quiz_question DROP COLUMN block_index;
ALTER TABLE quiz_question DROP COLUMN kind;
DROP TABLE source_excerpt;
