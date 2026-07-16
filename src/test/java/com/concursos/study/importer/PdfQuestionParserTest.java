package com.concursos.study.importer;

import com.concursos.study.question.QuestionCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PdfQuestionParserTest {

    private final PdfQuestionParser parser = new PdfQuestionParser();

    @Test
    void detectaBancaAnoDisciplinaCategoriaEGabarito() {
        String texto = """
                CONCURSO PUBLICO DATAPREV
                CEBRASPE 2023

                CONHECIMENTOS GERAIS

                LINGUA PORTUGUESA

                1 - Assinale a alternativa correta sobre concordancia verbal.
                A) Texto da alternativa A
                B) Texto da alternativa B
                C) Texto da alternativa C
                D) Texto da alternativa D
                E) Texto da alternativa E

                CONHECIMENTOS ESPECIFICOS

                DIREITO PREVIDENCIARIO

                2 - Sobre beneficios previdenciarios, e correto afirmar que
                A) Alternativa A
                B) Alternativa B
                C) Alternativa C

                GABARITO
                1 - A
                2 - C
                """;

        ParseResult result = parser.parse(texto);

        assertEquals("CEBRASPE", result.organization());
        assertEquals(2023, result.year());
        assertEquals(2, result.questions().size());
        assertEquals(0, result.failures().size());

        ParsedQuestion q1 = result.questions().get(0);
        assertEquals("Lingua Portuguesa", q1.subject());
        assertEquals(QuestionCategory.GERAL, q1.category());
        assertEquals("A", q1.correctAnswer());

        ParsedQuestion q2 = result.questions().get(1);
        assertEquals("Direito Previdenciario", q2.subject());
        assertEquals(QuestionCategory.ESPECIFICO, q2.category());
        assertEquals("C", q2.correctAnswer());
    }

    @Test
    void registraFalhaQuandoFaltamAlternativas() {
        String texto = """
                1 - Questao sem alternativas suficientes
                A) unica alternativa
                """;

        ParseResult result = parser.parse(texto);

        assertEquals(0, result.questions().size());
        assertEquals(1, result.failures().size());
        assertEquals(1, result.failures().get(0).number());
    }

    @Test
    void gabaritoInlineTemPrioridadeSobreTabela() {
        String texto = """
                1 - Pergunta com gabarito embutido
                A) alt a
                B) alt b
                Gabarito: B

                GABARITO
                1 - A
                """;

        ParseResult result = parser.parse(texto);
        assertEquals(1, result.questions().size());
        assertEquals("B", result.questions().get(0).correctAnswer());
    }

    @Test
    void alternativaQuebradaEmVariasLinhasEhCapturadaPorInteiro() {
        String texto = """
                1
                É preciso estar atento e forte.
                (A) Existe uma oração subordinada que exerce função de sujeito
                em relação à oração principal.
                (B) Nota-se uma oração subordinada substantiva, em que a
                segunda oração exerce função própria de um substantivo em
                relação à oração principal.
                (C) Observa-se uma oração subordinada reduzida de infinitivo.
                """;

        ParseResult result = parser.parse(texto);

        assertEquals(1, result.questions().size());
        ParsedQuestion q1 = result.questions().get(0);
        assertEquals("Existe uma oração subordinada que exerce função de sujeito em relação à oração principal.",
                q1.alternativeA());
        assertEquals("Nota-se uma oração subordinada substantiva, em que a segunda oração exerce função própria "
                + "de um substantivo em relação à oração principal.", q1.alternativeB());
    }

    @Test
    void semBancaOuAnoRetornaNull() {
        String texto = """
                1 - Pergunta generica
                A) alt a
                B) alt b
                """;

        ParseResult result = parser.parse(texto);
        assertNull(result.organization());
        assertNull(result.year());
    }
}
