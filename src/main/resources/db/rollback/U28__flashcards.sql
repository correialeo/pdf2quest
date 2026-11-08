-- Desfaz V28. Rodar manualmente e depois: DELETE FROM flyway_schema_history WHERE version = '28';
ALTER TABLE quiz DROP COLUMN flashcards;
DROP TABLE flashcard_rating;
DROP TABLE flashcard;
