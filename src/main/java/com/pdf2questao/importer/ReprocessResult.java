package com.pdf2questao.importer;

import java.util.List;

public record ReprocessResult(
        int updatedQuestions,
        int subjectsFilled,
        int images,
        int passages,
        List<Integer> notFoundNumbers,
        String error
) {

    static ReprocessResult error(String message) {
        return new ReprocessResult(0, 0, 0, 0, List.of(), message);
    }
}
