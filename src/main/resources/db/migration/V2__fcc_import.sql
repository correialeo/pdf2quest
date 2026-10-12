-- Importacao FCC: marcacao de questoes para revisao/anuladas e relatorio de importacao.
ALTER TABLE question ADD COLUMN needs_review boolean DEFAULT 0;
ALTER TABLE question ADD COLUMN annulled boolean DEFAULT 0;
ALTER TABLE question ADD COLUMN review_reason varchar(500);
ALTER TABLE import_job ADD COLUMN report clob;
