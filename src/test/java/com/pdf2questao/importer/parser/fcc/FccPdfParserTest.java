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

    // Trechos reais do OCR: lista com bolinhas e pontilhado, itens romanos e alternativas de romanos grudados.
    private static final String LISTAS = """
            ⟦p:1⟧
            PROVA 3 - CONHECIMENTOS ESPECÍFICOS

            1. O Patrimônio Liquido de uma empresa era composto das contas a seguir:

            ' Capital Social ..... ici esierieneasereneneemcaieiecen nessa naseonecase TODODDO
            “ Reserva Legal ........ seen meneeneeeecrcecao TSDDDO

            No ano de 2025, a empresa estabelece a seguinte destinação:

            “ Reserva Legal: constituída nos termos da Lei nº 6404/1976.
            " Retenção de Lucros: saldo remanescente.

            Com base nessas informações,

            (A) um.
            (B) dois.
            (C) tres.
            (D) quatro.
            (E) cinco.

            2. Considere as seguintes transações:

            I. Recebeu um terreno em doação.
            II. Vendeu ações que estavam em Tesouraria.
            Iv. Pagou dividendos.
            As transações listadas são reais.

            As transações que alteraram o Patrimônio foram:
            (A) IIIleV, apenas.

            (B) IeTII,apenas.

            (C) I, IIIe Iv.

            (D) IelIVv, apenas.

            (E) Nenhuma delas.
            """;

    @Test
    void listasDoEnunciadoFicamUmItemPorLinha() {
        ParsedQuestion q = parser.parse(LISTAS).questions().get(0);

        assertTrue(q.statement().contains("\n• Capital Social ..... 1.000.000\n• Reserva Legal ..... 150.000\n"),
                q.statement());
        assertTrue(q.statement().contains("\n• Reserva Legal: constituída"), q.statement());
        assertTrue(q.statement().contains("\n• Retenção de Lucros"), q.statement());
    }

    @Test
    void alternativasDeRomanosSaemLegiveis() {
        ParsedQuestion q = parser.parse(LISTAS).questions().get(1);

        assertTrue(q.statement().contains("\nII. Vendeu ações"), q.statement());
        assertTrue(q.statement().contains("\nIV. Pagou dividendos.\nAs transações listadas"), q.statement());
        assertEquals("III e V, apenas.", q.alternativeA());
        assertEquals("I e II, apenas.", q.alternativeB());
        assertEquals("I, III e IV.", q.alternativeC());
        assertEquals("I e IV, apenas.", q.alternativeD());
        assertEquals("Nenhuma delas.", q.alternativeE());
        // "IelIVv": o "l" podia ser um I; a leitura e um palpite.
        assertTrue(q.needsReview());
        assertTrue(q.reviewReason().contains("romanos"), q.reviewReason());
    }

    @Test
    void alternativaNuncaFicaEscondidaNemComBarraSolta() {
        assertEquals("não fez nenhum registro.", FccPdfParser.cleanAlternative("⟦h⟧não fez nenhum registro.⟦/h⟧"));
        assertEquals("reconheceu a perda.", FccPdfParser.cleanAlternative("| reconheceu a perda."));
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

    // Outro layout da FCC: caderno unico, sem "PROVA N -", secoes "CONHECIMENTOS ..." sozinhas na linha e um
    // rodape de outro orgao ("TRT15 ...") repetido em todas as paginas.
    private static String page(int n, String body) {
        return "\u27E6p:" + n + "\u27E7\nTRT15 Analista Judici\u00e1rio Caderno 1\n\n" + body
                + "\nTRT15 Analista Judici\u00e1rio Caderno 1 " + n + "\n";
    }

    private static final String ALTS = "(A) um.\n(B) dois.\n(C) tr\u00eas.\n(D) quatro.\n(E) cinco.\n";

    private static final String CADERNO_UNICO = "\u27E6p:1\u27E7\nTRIBUNAL REGIONAL DO TRABALHO\nCaderno de provas - Agosto/2024\n"
            + "Leia as instru\u00e7\u00f5es com aten\u00e7\u00e3o antes de iniciar a prova.\n\nCONHECIMENTOS GERAIS\n"
            + page(2, "\nL\u00cdNGUA PORTUGUESA\n\n1. Enunciado da primeira quest\u00e3o de portugu\u00eas.\n" + ALTS)
            + page(3, "\n2. Enunciado da segunda quest\u00e3o de portugu\u00eas.\n" + ALTS)
            + page(4, "\nCONHECIMENTOS ESPEC\u00cdFICOS\n\nDireito do Trabalho\n\n3. Enunciado da terceira quest\u00e3o.\n" + ALTS)
            + page(5, "\n4. Enunciado da quarta quest\u00e3o.\n" + ALTS);

    @Test
    void cadernoUnicoSemTituloDeProvaUsaSecoesRodapeGenericoECapa() {
        ParseResult result = parser.parse(CADERNO_UNICO);

        assertEquals(List.of(1, 2, 3, 4), result.questions().stream().map(ParsedQuestion::number).toList());
        assertTrue(result.failures().isEmpty());
        assertEquals("FCC", result.organization());
        assertEquals(2024, result.year());
        assertEquals("Conhecimentos Gerais", result.title());
        assertEquals(QuestionCategory.GERAL, result.questions().get(0).category());
        assertEquals(QuestionCategory.GERAL, result.questions().get(1).category());
        assertEquals(QuestionCategory.ESPECIFICO, result.questions().get(2).category());
        assertEquals(QuestionCategory.ESPECIFICO, result.questions().get(3).category());
        assertEquals("L\u00cdNGUA PORTUGUESA", result.questions().get(0).subject());
        assertEquals("Direito do Trabalho", result.questions().get(2).subject());
        for (ParsedQuestion q : result.questions()) {
            assertFalse((q.statement() + q.alternativeE()).contains("TRT15"), q.statement());
            assertNull(q.passage(), "a capa nao vira texto de apoio");
        }
    }

    @Test
    void anoEOTituloVemDaCapaEDoNomeDaProva() {
        String text = "\u27E6p:1\u27E7\nGoverno\nMar\u00e7o/2026\nResultado em 2025 e 2025 e 2025 e 2025\n"
                + "\u27E6p:2\u27E7\nPROVA 2 \u2014- CONHECIMENTOS B\u00c1SICOS\n\n1. Enunciado completo da quest\u00e3o.\n" + ALTS;

        ParseResult result = parser.parse(text);

        assertEquals(2026, result.year(), "o ano mais repetido no texto nao pode ganhar da capa");
        assertEquals("Prova 2 \u2013 Conhecimentos B\u00e1sicos", result.title());
        assertEquals(QuestionCategory.GERAL, result.questions().get(0).category());
    }

    @Test
    void ruidoDeBordaSoValeParaLinhasQueRepetemEmVariasPaginas() {
        Set<String> noise = FccPdfParser.edgeNoise(CADERNO_UNICO);

        assertTrue(noise.contains("trt analista judiciário caderno"), noise.toString());
        assertFalse(noise.stream().anyMatch(n -> n.contains("enunciado")));
        assertTrue(FccPdfParser.edgeNoise("\u27E6p:1\u27E7\nrodape repetido\n\u27E6p:2\u27E7\nrodape repetido\n").isEmpty(),
                "com poucas paginas nao ha como saber o que e repeticao");
    }

    private static final String U = "\u27E6u\u27E7";
    private static final String UE = "\u27E6/u\u27E7";

    @Test
    void sublinhadoDoOcrChegaAoEnunciadoAsAlternativasEOTextoDeApoio() {
        String text = "\u27E6p:1\u27E7\nPROVA 1 \u2013 CONHECIMENTOS GERAIS\n\n"
                + "Aten\u00e7\u00e3o: Para responder \u00e0s quest\u00f5es de n\u00fameros 1 a 2, baseie-se no texto.\n\n"
                + "O texto base fala de alguma coisa.\n\n"
                + "1. O passado funciona como a miragem da felicidade " + U + "que teria sido poss\u00edvel se" + UE + "\n"
                + U + "tiv\u00e9ssemos feito a escolha" + UE + "\n"
                + "Mant\u00eam-se o sentido substituindo-se o segmento sublinhado por\n"
                + "(A) para a qual a possibilidade de acerto seria leg\u00edtima.\n"
                + "(B) " + U + "dada como certa" + UE + " no caso de decorrer de uma melhor sele\u00e7\u00e3o.\n"
                + "(C) cuja poss\u00edvel escolha fosse dada por n\u00f3s como a mais perfeita.\n"
                + "(D) em que sua plena realiza\u00e7\u00e3o ensejaria a corre\u00e7\u00e3o da escolha.\n"
                + "(E) " + U + "com\u00e7ar" + UE + " de novo.\n\n"
                + "2. Outro enunciado completo com " + U + "uma palavra" + UE + " sublinhada.\n(A) um.\n(B) dois.\n(C) tr\u00eas.\n(D) quatro.\n(E) cinco.\n";

        ParseResult result = parser.parse(text);
        ParsedQuestion q1 = result.questions().get(0);

        assertEquals(2, result.questions().size());
        assertTrue(result.failures().isEmpty(), result.failures().toString());
        // o sublinhado que atravessa duas linhas vira um so, sem emenda no meio
        assertTrue(q1.statement().contains(U + "que teria sido poss\u00edvel se tiv\u00e9ssemos feito a escolha" + UE),
                q1.statement());
        assertEquals(U + "dada como certa" + UE + " no caso de decorrer de uma melhor sele\u00e7\u00e3o.", q1.alternativeB());
        assertEquals(U + "com\u00e7ar" + UE + " de novo.", q1.alternativeE());
        assertEquals("para a qual a possibilidade de acerto seria leg\u00edtima.", q1.alternativeA());
        assertFalse(q1.needsReview(), q1.reviewReason());
        assertTrue(q1.passage().contains("texto base"), q1.passage());
        assertTrue(q1.passage().startsWith("Aten\u00e7\u00e3o:"), "sem o sublinhado da capa, sem alterar o texto");
        assertTrue(parser.parse(text).questions().get(1).statement().contains(U + "uma palavra" + UE));
    }

    @Test
    void hifenizacaoDentroDeSublinhadoEJuncaoDeLinhas() {
        assertEquals(U + "Secretaria Receita Federal" + UE + " do Brasil",
                FccPdfParser.joinLines(List.of(U + "Secretaria Re-" + UE, U + "ceita Federal" + UE + " do Brasil")));
        assertEquals("a " + U + "frase inteira" + UE + " segue",
                FccPdfParser.joinLines(List.of("a " + U + "frase" + UE, U + "inteira" + UE + " segue")));
        assertEquals("sem " + U + "um" + UE + " e " + U + "outro" + UE,
                FccPdfParser.joinLines(List.of("sem " + U + "um" + UE, "e " + U + "outro" + UE)));
    }

    @Test
    void textoComSublinhadoDecideIgualAoTextoSemSublinhado() {
        String plain = "\u27E6p:1\u27E7\nPROVA 1 \u2013 CONHECIMENTOS GERAIS\n\n1. Enunciado da quest\u00e3o completo aqui.\n"
                + "(A) um.\n(B) dois.\n(C) tr\u00eas.\n(D) quatro.\n(E) cinco.\n";
        String marked = plain.replace("Enunciado da", U + "Enunciado da" + UE).replace("(B) dois.", "(B) " + U + "dois" + UE + ".");

        ParsedQuestion a = parser.parse(plain).questions().get(0);
        ParsedQuestion b = parser.parse(marked).questions().get(0);

        assertEquals(a.needsReview(), b.needsReview());
        assertEquals(com.pdf2questao.importer.parser.support.RichText.plain(b.statement()), a.statement());
        assertEquals("dois.", a.alternativeB());
        assertEquals(U + "dois" + UE + ".", b.alternativeB());
    }

    @Test
    void citaSublinhadoSemNenhumSublinhadoDetectadoVaiParaRevisao() {
        String semMarca = "\u27E6p:1\u27E7\nPROVA 1 \u2013 CONHECIMENTOS GERAIS\n\n1. Substitua o segmento sublinhado por outro equivalente.\n" + ALTS;

        ParsedQuestion q = parser.parse(semMarca).questions().get(0);

        assertTrue(q.needsReview());
        assertTrue(q.reviewReason().contains("sublinhad"), q.reviewReason());
    }
}
