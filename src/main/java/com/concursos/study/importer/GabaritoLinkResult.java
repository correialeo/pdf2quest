package com.concursos.study.importer;

import java.util.List;

public record GabaritoLinkResult(
        int matched,
        int totalGabarito,
        int totalQuestions,
        List<Integer> unmatchedQuestionNumbers,
        String errorMessage
) {
}
