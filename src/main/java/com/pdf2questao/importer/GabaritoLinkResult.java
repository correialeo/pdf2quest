package com.pdf2questao.importer;

import java.util.List;

public record GabaritoLinkResult(
        int matched,
        int totalGabarito,
        int totalQuestions,
        List<Integer> unmatchedQuestionNumbers,
        String errorMessage,
        List<Integer> annulledQuestionNumbers,
        List<String> report
) {

    public GabaritoLinkResult(int matched, int totalGabarito, int totalQuestions,
                              List<Integer> unmatchedQuestionNumbers, String errorMessage) {
        this(matched, totalGabarito, totalQuestions, unmatchedQuestionNumbers, errorMessage, List.of(), List.of());
    }
}
