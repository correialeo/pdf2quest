package com.pdf2questao.importer;

import com.pdf2questao.question.QuestionCategory;

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
        QuestionCategory category
) {
}
