package com.pdf2questao.importer.parser.cebraspe;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CebraspeGabaritoParserTest {

    private final CebraspeGabaritoParser parser = new CebraspeGabaritoParser();

    private static final String GABARITO = """
            1 2 3 4 0 0
            C E X C 0 0
            Item
            CONHECIMENTO GERAIS PARA OS CARGOS DE NÍVEL MÉDIO
            1 2 3 4 0 0
            E E C C 0 0
            CONHECIMENTO GERAIS PARA OS CARGOS DE NÍVEL SUPERIOR
            51 52 53 0
            C E E 0
            CARGO 7: ANALISTA DE TECNOLOGIA DA INFORMAÇÃO – PERFIL: DESENVOLVIMENTO DE SOFTWARE
            51 52 53 0
            E C C 0
            CARGO 8: ANALISTA DE TECNOLOGIA DA INFORMAÇÃO – PERFIL: ENGENHARIA CIVIL
            """;

    @Test
    void juntaPaginasDosTitulosSeparadosPorPontoEVirgula() {
        Map<Integer, String> answers = parser.parse(GABARITO, "nivel superior; Desenvolvimento de Software");

        assertEquals(Map.of(1, "E", 2, "E", 3, "C", 4, "C", 51, "C", 52, "E", 53, "E"), answers);
    }

    @Test
    void ignoraItemAnuladoETituloInexistente() {
        assertEquals(Map.of(1, "C", 2, "E", 4, "C"), parser.parse(GABARITO, "NÍVEL MÉDIO"));
        assertTrue(parser.parse(GABARITO, "CARGO 99").isEmpty());
    }
}
