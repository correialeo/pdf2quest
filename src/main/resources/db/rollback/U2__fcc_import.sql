-- Desfaz V2. Rodar manualmente (sqlite3 pdf2questao.db < este arquivo) e depois remover a
-- linha da versao 2: DELETE FROM flyway_schema_history WHERE version = '2';
-- Faca backup do banco antes: as colunas abaixo perdem os dados.
ALTER TABLE import_job DROP COLUMN report;
ALTER TABLE question DROP COLUMN review_reason;
ALTER TABLE question DROP COLUMN annulled;
ALTER TABLE question DROP COLUMN needs_review;
