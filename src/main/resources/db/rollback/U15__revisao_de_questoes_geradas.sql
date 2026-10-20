-- Desfaz V15. Rodar manualmente e depois: DELETE FROM flyway_schema_history WHERE version = '15';
-- Faca backup antes: revisoes, versoes anteriores das questoes e conteudo programatico sao perdidos.
DROP INDEX IF EXISTS ix_edital_topic_profile;
DROP TABLE IF EXISTS edital_topic;
DROP INDEX IF EXISTS ix_question_revision_question;
DROP TABLE IF EXISTS question_revision;
DROP TABLE IF EXISTS question_review;
