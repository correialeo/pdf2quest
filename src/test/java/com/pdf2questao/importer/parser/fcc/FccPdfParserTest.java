package com.pdf2questao.importer.parser.fcc;

import com.pdf2questao.importer.ParseResult;
import com.pdf2questao.importer.ParsedQuestion;
import com.pdf2questao.question.QuestionCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FccPdfParserTest {

    private final FccPdfParser parser = new FccPdfParser();

    // Saida de OCR (psm 4) com os defeitos reais: marcadores {A) e (0), cabecalho com lixo
    // de codigo de barras, rodape com numero de pagina colado e quebra de palavra em fim de linha.
    private static final String OCR = """
            ⟦p:1⟧
            Concurso Publico
            CONHECIMENTOS BÁSICOS (P2)
            Verifique se este caderno contém 80 questões numeradas de 1 a 80.
            ⟦p:2⟧
            MM  cademo de Prova 'g!, Tipo 001

            PROVA 2 —- CONHECIMENTOS BÁSICOS
            Direito: Constitucional, Administrativo, Civil, Empresarial, Financeiro e Penal

            1. Lei federal estabelece, para a pessoa jurídica, o dever de informar à Secretaria Especial da Re-
            ceita Federal do Brasil, os incentivos fiscais. Nessa hipótese, referida exigência

            {A) deve aplicar-se apenas aos benefícios fiscais federais.

            (B) é inconstitucional, por violar o princípio da simplicidade.

            (C) é razoável e proporcional, inexistindo violação às normas de pe-
            queno porte.

            (0) será constitucional, desde que prevista em lei complementar.

            {E) será constitucional, desde que a lei não preveja imposição de penalidade.

            2. Nos termos da Constituição Federal, a exigência de depósito prévio seria
            1. item solto de lista dentro do enunciado
            2. outro item solto

            (A) inadmissível.
            (B) inadmissível, sendo exigível.
            (C) inadmissível, ainda que prevista.
            (D) admissível, desde que prevista na lei.
            (E) admissível, desde que prevista na lei federal.
            4 FAZSP-Prova 2-Conhecimentos Básicos!
            ⟦p:3⟧
            Caderno de Prova 'B', Tipo 001 JANAINA

            Legislação Tributária

            Atenção: Para responder às questões de números 3 e 4, baseie-se no texto seguinte.

            O contribuinte deve emitir nota fiscal eletrônica.

            3. Segundo o texto, a emissão de nota fiscal
            (A) é facultativa.
            (B) é obrigatória.
            (C) depende de decreto.
            (D) é vedada.
            (E) é opcional.

            4. Considere a tabela a seguir e assinale a correta.
            (A) um.
            (B) dois.
            (C) tres.
            (D) quatro.
            (E) cinco.
            """;

    private ParseResult parse() {
        return parser.parse(OCR);
    }

    private ParsedQuestion question(int number) {
        return parse().questions().stream().filter(q -> q.number() == number).findFirst().orElseThrow();
    }

    @Test
    void importaTodasAsQuestoesEmSequencia() {
        ParseResult result = parse();

        assertEquals(List.of(1, 2, 3, 4), result.questions().stream().map(ParsedQuestion::number).toList());
        assertTrue(result.failures().isEmpty());
        assertEquals(QuestionCategory.GERAL, question(1).category());
        assertEquals(2, question(1).page());
        assertEquals(3, question(3).page());
    }

    @Test
    void toleraMarcadoresComErroDeOcr() {
        ParsedQuestion q = question(1);

        assertTrue(q.alternativeA().startsWith("deve aplicar-se"));
        assertTrue(q.alternativeD().startsWith("será constitucional, desde que prevista"));
        assertTrue(q.alternativeE().startsWith("será constitucional, desde que a lei"));
        assertFalse(q.needsReview(), q.reviewReason());
    }

    @Test
    void desfazHifenizacaoDeFimDeLinha() {
        ParsedQuestion q = question(1);

        assertTrue(q.statement().contains("Secretaria Especial da Receita Federal"));
        assertTrue(q.alternativeC().contains("de pequeno porte"));
    }

    @Test
    void ignoraCabecalhoRodapeECapa() {
        for (ParsedQuestion q : parse().questions()) {
            String all = q.statement() + q.alternativeA() + q.alternativeE();
            assertFalse(all.contains("cademo"), all);
            assertFalse(all.contains("FAZSP"), all);
            assertFalse(all.contains("Caderno de Prova"), all);
        }
    }

    @Test
    void itensNumeradosDentroDoEnunciadoNaoViramQuestao() {
        ParsedQuestion q = question(2);

        assertTrue(q.statement().contains("1. item solto"));
        assertTrue(q.statement().contains("2. outro item"));
        assertEquals(4, parse().questions().size());
    }

    @Test
    void tituloDeSecaoViraSugestaoNaPrimeiraQuestaoDepoisDele() {
        assertEquals("Legislação Tributária", question(3).sectionHeading());
        assertEquals("Legislação Tributária", question(3).subject());
        assertEquals("Legislação Tributária", question(4).subject());
        assertNull(question(4).sectionHeading());
    }

    @Test
    void textoDeApoioVaiParaAsQuestoesCitadasNoEnunciado() {
        assertNotNull(question(3).passage());
        assertTrue(question(3).passage().contains("nota fiscal eletrônica"));
        assertEquals(question(3).passage(), question(4).passage());
        assertNull(question(2).passage());
    }

    @Test
    void marcaParaRevisaoQuestaoQueCitaTabela() {
        assertTrue(question(4).needsReview());
        assertTrue(question(4).reviewReason().contains("tabela"));
    }

    @Test
    void marcaParaRevisaoQuandoFaltaAlternativa() {
        String text = "⟦p:1⟧\nPROVA 1 – CONHECIMENTOS GERAIS\n\n1. Enunciado completo da questão sobre algo.\n(A) um.\n(B) dois.\n(D) quatro.\n(E) cinco.\n";

        ParsedQuestion q = parser.parse(text).questions().get(0);

        assertTrue(q.needsReview());
        assertTrue(q.reviewReason().contains("alternativa C"));
    }

    @Test
    void especificosSaoCategoriaEspecifico() {
        String text = "⟦p:1⟧\nPROVA 3 —- CONHECIMENTOS ESPECÍFICOS\n\n1. Enunciado completo da questão sobre algo.\n(A) um.\n(B) dois.\n(C) tres.\n(D) quatro.\n(E) cinco.\n";

        assertEquals(QuestionCategory.ESPECIFICO, parser.parse(text).questions().get(0).category());
    }

    @Test
    void numerosDoTextoDeApoio() {
        assertEquals(Set.of(54, 55, 58), FccPdfParser.passageNumbers(
                "Atenção: Considere o texto a seguir para responder às questões de números 54, 55 e 58."));
        assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7, 8), FccPdfParser.passageNumbers(
                "Atenção: Para responder às questões de números 1 a 8, baseie-se no texto seguinte."));
        assertTrue(FccPdfParser.passageNumbers("Um texto qualquer sem indicação.").isEmpty());
    }
}
