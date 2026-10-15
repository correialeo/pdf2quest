package com.pdf2questao.question;

/** Tipos de questao aceitos pela importacao estruturada (JSON/CSV). */
public enum QuestionType {
    /** Alternativas A a E (de 2 a 5), uma correta. */
    MULTIPLA_ESCOLHA,
    /** Item Certo/Errado (Cebraspe): alternativas C = Certo e E = Errado, como na importacao de PDF. */
    CERTO_ERRADO
}
