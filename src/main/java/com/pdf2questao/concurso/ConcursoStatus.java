package com.pdf2questao.concurso;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Andamento do concurso, do interesse ao resultado. */
@Getter
@RequiredArgsConstructor
public enum ConcursoStatus {
    INTERESSE("Interesse"),
    INSCRITO("Inscrito"),
    ESTUDANDO("Estudando"),
    PROVA_FEITA("Prova feita"),
    APROVADO("Aprovado"),
    REPROVADO("Não aprovado"),
    ARQUIVADO("Arquivado");

    private final String label;
}
