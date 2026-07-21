package com.pdf2questao.importer.parser.cesgranrio;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CesgranrioGabaritoParserTest {

    private final CesgranrioGabaritoParser parser = new CesgranrioGabaritoParser();

    @Test
    void parseiaBasicasEColunaDaProvaInformada() {
        String texto = """
                CONHECIMENTOS BÁSICOS
                CONHECIMENTOS TRANSVERSAIS
                1 -  D 2 - C 3 - D
                LÍNGUA PORTUGUESA
                21 -  D 22 - D

                CONHECIMENTOS ESPECÍFICOS
                Prova 1 Prova 2 Prova 3
                ANALISTA / ADMINISTRAÇÃO
                ANALISTA / ANÁLISE DE SISTEMAS CIBERSEGURANÇA
                ANALISTA / ANÁLISE DE SISTEMAS DESENVOLVIMENTO
                36 - B 36 - A 36 - A
                37 - C 37 - E 37 - C
                70 - A 70 - C 70 - B
                """;

        Map<Integer, String> answers = parser.parse(texto,
                "ANALISTA / ANÁLISE DE SISTEMAS DESENVOLVIMENTO - PROVA 3");

        assertEquals(8, answers.size());
        assertEquals("D", answers.get(1));
        assertEquals("D", answers.get(21));
        assertEquals("A", answers.get(36));
        assertEquals("C", answers.get(37));
        assertEquals("B", answers.get(70));
    }

    @Test
    void tituloInexistenteRetornaMapaVazio() {
        String texto = """
                CONHECIMENTOS ESPECÍFICOS
                Prova 1 Prova 2
                ANALISTA / ADMINISTRAÇÃO
                ANALISTA / DIREITO
                36 - B 36 - A
                """;

        Map<Integer, String> answers = parser.parse(texto, "PERFIL INEXISTENTE");

        assertTrue(answers.isEmpty());
    }
}
