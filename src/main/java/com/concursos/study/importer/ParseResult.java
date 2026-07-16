package com.concursos.study.importer;

import java.util.List;

public record ParseResult(
        String organization,
        Integer year,
        List<ParsedQuestion> questions,
        List<ParseFailure> failures
) {
}
