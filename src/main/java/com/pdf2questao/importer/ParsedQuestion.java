package com.pdf2questao.importer;

import com.pdf2questao.question.QuestionCategory;

/**
 * Textos podem conter marcacao {@link com.pdf2questao.importer.parser.support.RichText}.
 * {@code needsReview}/{@code reviewReason} sinalizam leitura incerta (OCR, imagem, tabela, codigo) e
 * {@code sectionHeading} so vem preenchido na primeira questao depois de um titulo de secao detectado.
 */
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
        Integer page,
        boolean needsReview,
        String reviewReason,
        String sectionHeading
) {

    public ParsedQuestion(int number, String statement, String alternativeA, String alternativeB,
                          String alternativeC, String alternativeD, String alternativeE, String correctAnswer,
                          String subject, QuestionCategory category, String passage, Integer page) {
        this(number, statement, alternativeA, alternativeB, alternativeC, alternativeD, alternativeE,
                correctAnswer, subject, category, passage, page, false, null, null);
    }

    public ParsedQuestion(int number, String statement, String alternativeA, String alternativeB,
                          String alternativeC, String alternativeD, String alternativeE, String correctAnswer,
                          String subject, QuestionCategory category) {
        this(number, statement, alternativeA, alternativeB, alternativeC, alternativeD, alternativeE,
                correctAnswer, subject, category, null, null);
    }

    public ParsedQuestion withCorrectAnswer(String answer) {
        return new ParsedQuestion(number, statement, alternativeA, alternativeB, alternativeC, alternativeD,
                alternativeE, answer, subject, category, passage, page, needsReview, reviewReason, sectionHeading);
    }

    public ParsedQuestion withSubject(String newSubject) {
        return new ParsedQuestion(number, statement, alternativeA, alternativeB, alternativeC, alternativeD,
                alternativeE, correctAnswer, newSubject, category, passage, page, needsReview, reviewReason,
                sectionHeading);
    }
}
