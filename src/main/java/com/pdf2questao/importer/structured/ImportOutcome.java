package com.pdf2questao.importer.structured;

/** O que a confirmacao gravou. {@code error} preenchido = nada foi gravado. */
public record ImportOutcome(Long importJobId, int created, int updated, int skipped, String error) {

    static ImportOutcome failure(String error) {
        return new ImportOutcome(null, 0, 0, 0, error);
    }
}
