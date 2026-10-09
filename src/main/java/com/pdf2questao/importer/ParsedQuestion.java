package com.pdf2questao.importer;

import com.pdf2questao.question.QuestionCategory;

/** Textos podem conter marcacao {@link com.pdf2questao.importer.parser.support.RichText}. */
public record ParsedQuestion(
        int number,
        String statement,
        String alternativeA,
        String alternativeB,
        String alternativeC,
        String alternativeD,
        String alternativeE,
        String correctAnswer,
        String subject,
        QuestionCategory category,
        String passage,
        Integer page
) {

    public ParsedQuestion(int number, String statement, String alternativeA, String alternativeB,
                          String alternativeC, String alternativeD, String alternativeE, String correctAnswer,
                          String subject, QuestionCategory category) {
        this(number, statement, alternativeA, alternativeB, alternativeC, alternativeD, alternativeE,
                correctAnswer, subject, category, null, null);
    }

    public ParsedQuestion withCorrectAnswer(String answer) {
        return new ParsedQuestion(number, statement, alternativeA, alternativeB, alternativeC, alternativeD,
                alternativeE, answer, subject, category, passage, page);
    }
}
