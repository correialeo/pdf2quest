package com.concursos.study.importer;

import com.concursos.study.question.QuestionCategory;

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
