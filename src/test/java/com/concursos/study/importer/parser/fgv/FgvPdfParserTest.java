package com.concursos.study.importer.parser.fgv;

import com.concursos.study.importer.ParseResult;
import com.concursos.study.importer.ParsedQuestion;
import com.concursos.study.question.QuestionCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FgvPdfParserTest {

    private final FgvPdfParser parser = new FgvPdfParser();

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

    @Test
    void textoDeApoioComNomeProprioNaoViraDisciplinaNemPoluiAlternativa() {
        String texto = """
                CONHECIMENTOS GERAIS

                LINGUA INGLESA

                18 - Question about the guide
                A) Alternative A
                B) Alternative B
                C) Alternative C
                D) Alternative D
                E) Alternative E
                Use the following TEXT to answer the next two questions.
                It's not often we write about printers, but this one is different.
                It prints up to 20 pages per minute from any PC.
                Louis Ramirez
                http://example.com/

                19 - What does the pronoun refer to
                A) the printer
                B) the PC
                C) the review
                D) the guide
                E) the manual
                """;

        ParseResult result = parser.parse(texto);

        assertEquals(2, result.questions().size());

        ParsedQuestion q18 = result.questions().get(0);
        assertEquals("Lingua Inglesa", q18.subject());
        assertEquals("Alternative E", q18.alternativeE());

        ParsedQuestion q19 = result.questions().get(1);
        assertEquals("Lingua Inglesa", q19.subject());
    }

    @Test
    void conhecimentosEspecificosSemCabecalhoNaoHerdaDisciplinaDosConhecimentosGerais() {
        String texto = """
                CONHECIMENTOS GERAIS

                DIREITO CONSTITUCIONAL

                1 - Pergunta de direito constitucional
                A) Alternativa A
                B) Alternativa B
                C) Alternativa C

                CONHECIMENTOS ESPECIFICOS

                2 - Pergunta especifica sem cabecalho de disciplina
                A) Alternativa A
                B) Alternativa B
                C) Alternativa C
                """;

        ParseResult result = parser.parse(texto);

        ParsedQuestion q1 = result.questions().get(0);
        assertEquals("Direito Constitucional", q1.subject());
        assertEquals(QuestionCategory.GERAL, q1.category());

        ParsedQuestion q2 = result.questions().get(1);
        assertNull(q2.subject());
        assertEquals(QuestionCategory.ESPECIFICO, q2.category());
    }

    @Test
    void tituloDeDisciplinaComPreposicaoMantemMinusculaExcetoNoInicio() {
        String texto = """
                CONHECIMENTOS GERAIS

                LEGISLACAO ACERCA DE SEGURANCA DA
                INFORMACAO E PROTECAO DE DADOS

                1 - Pergunta qualquer
                A) Alternativa A
                B) Alternativa B
                C) Alternativa C
                """;

        ParseResult result = parser.parse(texto);

        assertEquals("Legislacao Acerca de Seguranca da Informacao e Protecao de Dados",
                result.questions().get(0).subject());
    }

    @Test
    void rodapeDePaginaRepetidoNaoPoluiAlternativaNoMeioDaPagina() {
        String rodape = """
                pcimarkpci MjgwNDo4MmM0OjAwYjY6NDUwMDpkZDY2OmY0OWY6NmYwODplY2Zj:V2VkLCAxNSBKdWwgMjAyNiAyMzozNDozOCAtMDMwMA==
                www.provaonline.com.br
                INSTITUTO EXEMPLO DE CONCURSOS PUBLICOS
                PROVA MODELO - PAGINA 1
                """;

        String texto = """
                CONHECIMENTOS GERAIS

                LINGUA PORTUGUESA

                """ + rodape + """

                1 - Assinale a alternativa correta
                A) Alternativa A
                B) Alternativa B
                C) Alternativa C
                D) Alternativa D
                """ + rodape + """
                E) Alternativa E

                2 - Segunda pergunta
                A) Alternativa A
                B) Alternativa B
                """ + rodape + """
                C) Alternativa C
                """;

        ParseResult result = parser.parse(texto);

        assertEquals(2, result.questions().size());

        ParsedQuestion q1 = result.questions().get(0);
        assertEquals("Lingua Portuguesa", q1.subject());
        assertEquals("Alternativa D", q1.alternativeD());
        assertEquals("Alternativa E", q1.alternativeE());

        ParsedQuestion q2 = result.questions().get(1);
        assertEquals("Alternativa C", q2.alternativeC());
    }
}
