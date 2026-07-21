package com.pdf2questao.importer.parser.cesgranrio;

import com.pdf2questao.importer.ParseResult;
import com.pdf2questao.importer.ParsedQuestion;
import com.pdf2questao.question.QuestionCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CesgranrioPdfParserTest {

    private final CesgranrioPdfParser parser = new CesgranrioPdfParser();

    @Test
    void ignoraInstrucoesNumeradasEParseiaQuestoesDaProva() {
        String texto = """
                LEIA ATENTAMENTE AS INSTRUÇÕES ABAIXO.
                01 - O candidato recebeu o material.
                FUNDAÇÃO CESGRANRIO
                BANCO NACIONAL DE DESENVOLVIMENTO ECONÔMICO E SOCIAL - BNDES
                EDITAL No 01/2024 - SELEÇÃO PÚBLICA
                CONHECIMENTOS BÁSICOS
                CONHECIMENTOS TRANSVERSAIS
                1
                Enunciado da primeira questão.
                (A)\tAlternativa A
                (B)\tAlternativa B em duas
                linhas.
                (C)\tAlternativa C
                (D)\tAlternativa D
                (E)\tAlternativa E
                LÍNGUA PORTUGUESA
                Texto de apoio que nao deve grudar na questao anterior.
                2
                Enunciado da segunda questão.
                (A)\tAlternativa A
                (B)\tAlternativa B
                (C)\tAlternativa C
                CONHECIMENTOS ESPECÍFICOS
                3
                Enunciado especifico.
                (A)\tAlternativa A
                (B)\tAlternativa B
                """;

        ParseResult result = parser.parse(texto);

        assertEquals("CESGRANRIO", result.organization());
        assertEquals(2024, result.year());
        assertEquals(3, result.questions().size());
        assertEquals(0, result.failures().size());

        ParsedQuestion q1 = result.questions().get(0);
        assertEquals("Conhecimentos Transversais", q1.subject());
        assertEquals(QuestionCategory.GERAL, q1.category());
        assertEquals("Alternativa B em duas linhas.", q1.alternativeB());

        ParsedQuestion q2 = result.questions().get(1);
        assertEquals("Lingua Portuguesa", q2.subject());
        assertEquals(QuestionCategory.GERAL, q2.category());
        assertEquals("Enunciado da segunda questão.", q2.statement());

        ParsedQuestion q3 = result.questions().get(2);
        assertEquals("Conhecimentos Especificos", q3.subject());
        assertEquals(QuestionCategory.ESPECIFICO, q3.category());
        assertNull(q3.correctAnswer());
    }

    @Test
    void registraFalhaQuandoFaltamAlternativas() {
        String texto = """
                CONHECIMENTOS BÁSICOS
                1
                Questao sem alternativas suficientes.
                (A)\tUnica alternativa
                """;

        ParseResult result = parser.parse(texto);

        assertEquals(0, result.questions().size());
        assertEquals(1, result.failures().size());
        assertEquals(1, result.failures().get(0).number());
    }
}
