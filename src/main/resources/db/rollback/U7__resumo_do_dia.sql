-- Desfaz V7 (as paginas continuam, so perdem o vinculo com o dia).
DROP INDEX IF EXISTS ux_study_page_summary_date;
ALTER TABLE study_page DROP COLUMN summary_date;
DELETE FROM flyway_schema_history WHERE version = '7';
