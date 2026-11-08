-- Flashcards: recordacao ativa antes das questoes, na revisao. Um ou mais cartoes por conceito ({#id}) da aula.
CREATE TABLE flashcard (
    id integer PRIMARY KEY AUTOINCREMENT,
    concurso_id bigint,
    lesson_id bigint NOT NULL,
    concept varchar(100) NOT NULL,
    -- pergunta, lacuna (cloze: "___" na frente) ou contraste
    kind varchar(20) NOT NULL,
    front clob NOT NULL,
    back clob NOT NULL,
    created_at timestamp
);
-- Autoavaliacao de cada cartao (errei, dificil, facil), na tela do quiz que o traz.
CREATE TABLE flashcard_rating (
    id integer PRIMARY KEY AUTOINCREMENT,
    flashcard_id bigint NOT NULL,
    quiz_id bigint,
    rating varchar(10) NOT NULL,
    rated_at timestamp
);
-- Cartoes que aparecem antes das questoes do quiz (ids separados por virgula, na ordem).
ALTER TABLE quiz ADD COLUMN flashcards varchar(2000);
