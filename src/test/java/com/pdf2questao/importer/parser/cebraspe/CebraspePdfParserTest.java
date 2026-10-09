package com.pdf2questao.importer.parser.cebraspe;

import com.pdf2questao.importer.ParseResult;
import com.pdf2questao.importer.ParsedQuestion;
import com.pdf2questao.question.QuestionCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CebraspePdfParserTest {

    private static final String IND = "⟦ind⟧";

    private final CebraspePdfParser parser = new CebraspePdfParser();

    @Test
    void itensViramQuestoesCertoErradoComComandoEContinuacaoRecuada() {
        String texto = "⟦p:1⟧-- CONHECIMENTOS ESPECÍFICOS --\n"
                + "Julgue os itens abaixo, relacionados com JavaScript, Web\n"
                + "Services e análise estática de código-fonte.\n"
                + "51 Para garantir uma correta compilação de um código escrito\n"
                + IND + "em JavaScript, é necessário declarar variáveis.\n"
                + "52 No SonarQube, a complexidade mede caminhos.\n"
                + "Com base no COBIT 2019, julgue os próximos itens. Espaço livre\n"
                + "53 No domínio DSS, há processos\n"
                + IND + "de continuidade.\n";

        ParseResult result = parser.parse(texto);

        assertEquals(3, result.questions().size());
        ParsedQuestion q51 = result.questions().get(0);
        assertEquals(51, q51.number());
        assertEquals(QuestionCategory.ESPECIFICO, q51.category());
        assertNull(q51.subject());
        assertEquals(1, q51.page());
        assertEquals("Julgue os itens abaixo, relacionados com JavaScript, Web Services e análise estática de "
                + "código-fonte.\n\nPara garantir uma correta compilação de um código escrito em JavaScript, "
                + "é necessário declarar variáveis.", q51.statement());
        assertEquals(CebraspePdfParser.CERTO, q51.alternativeC());
        assertEquals(CebraspePdfParser.ERRADO, q51.alternativeE());
        assertNull(q51.alternativeA());
        assertEquals("Com base no COBIT 2019, julgue os próximos itens.\n\nNo domínio DSS, há processos de continuidade.",
                result.questions().get(2).statement());
    }

    @Test
    void textoDeApoioDisciplinaERenumeracaoNaParteGeral() {
        String texto = "-- CONHECIMENTOS ESPECÍFICOS --\n"
                + "Julgue o item.\n"
                + "120 Item final.\n"
                + "-- PROVAS OBJETIVAS --\n"
                + "-- CONHECIMENTOS GERAIS --\n"
                + IND + "LÍNGUA PORTUGUESA\n"
                + "  A cibersegurança é um campo novo.\n"
                + "Internet: <economiasc.com> (com adaptações).\n"
                + "Julgue os itens que se seguem, com base no texto\n"
                + "precedente.\n"
                + "1 O texto trata de cibersegurança.\n"
                + "2 O texto é jornalístico.\n"
                + IND + "RACIOCÍNIO LÓGICO\n"
                + "Julgue os itens a seguir.\n"
                + "3 Toda proposição é verdadeira.\n";

        ParseResult result = parser.parse(texto);

        assertEquals(4, result.questions().size());
        ParsedQuestion q1 = result.questions().get(1);
        assertEquals(1, q1.number());
        assertEquals(QuestionCategory.GERAL, q1.category());
        assertEquals("Língua Portuguesa", q1.subject());
        assertEquals("A cibersegurança é um campo novo.\nInternet: <economiasc.com> (com adaptações).", q1.passage());
        assertEquals(q1.passage(), result.questions().get(2).passage());
        ParsedQuestion q3 = result.questions().get(3);
        assertEquals("Raciocínio Lógico", q3.subject());
        assertNull(q3.passage());
    }
}
