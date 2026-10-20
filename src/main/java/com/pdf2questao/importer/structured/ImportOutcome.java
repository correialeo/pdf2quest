package com.pdf2questao.importer.structured;

/**
 * O que a confirmacao gravou. {@code error} preenchido = nada foi gravado. {@code trainingSessionId}: sessao
 * criada com as respostas do usuario que vieram no arquivo ("suaResposta"), se houver.
 */
public record ImportOutcome(Long importJobId, int created, int updated, int skipped, String error,
                            Long trainingSessionId) {

    public ImportOutcome(Long importJobId, int created, int updated, int skipped, String error) {
        this(importJobId, created, updated, skipped, error, null);
    }

    static ImportOutcome failure(String error) {
        return new ImportOutcome(null, 0, 0, 0, error);
    }
}
