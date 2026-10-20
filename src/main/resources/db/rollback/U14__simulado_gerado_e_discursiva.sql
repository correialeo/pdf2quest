-- Desfaz V14. Rodar manualmente e depois: DELETE FROM flyway_schema_history WHERE version = '14';
-- Faca backup do banco antes: as discursivas (textos e correcoes) sao perdidas. As provas e blocos dos simulados
-- criados pelo Claude continuam, so perdem o vinculo de rascunho.
DROP TABLE IF EXISTS essay;
DROP INDEX IF EXISTS ix_generated_exam_job;
DROP TABLE IF EXISTS generated_exam;
