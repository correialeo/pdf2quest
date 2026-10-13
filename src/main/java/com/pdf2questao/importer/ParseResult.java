package com.pdf2questao.importer;

import java.util.List;

/** {@code title} e opcional: nome da prova/caderno quando o parser consegue le-lo do proprio PDF. */
public record ParseResult(
        String organization,
        Integer year,
        List<ParsedQuestion> questions,
        List<ParseFailure> failures,
        String title
) {

    public ParseResult(String organization, Integer year, List<ParsedQuestion> questions,
                       List<ParseFailure> failures) {
        this(organization, year, questions, failures, null);
    }
}
